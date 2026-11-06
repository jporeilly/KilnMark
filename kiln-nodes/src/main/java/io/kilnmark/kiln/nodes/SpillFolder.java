package io.kilnmark.kiln.nodes;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.FilterInputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryFlag;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.DigestInputStream;
import java.security.DigestOutputStream;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.IvParameterSpec;

/**
 * Where a sort or a join writes rows that do not fit in its memory limit: a folder of its own,
 * inside one folder for the process, under the system property {@code kilnmark.spill-dir} or else
 * {@code java.io.tmpdir}. On POSIX systems the folders are made readable by the server's user only
 * ({@code rwx------}); on Windows their access list is set to the folder's owner only, where the
 * file system allows it (otherwise they keep the temporary folder's, which is the user's own by
 * default). The node deletes its folder when it closes, whether it finished, failed or was stopped.
 *
 * <p>Each process folder holds a lock file the process keeps locked while it runs. Folders whose
 * lock can be taken belong to a process that has gone (a crash), on this machine or another sharing
 * the folder, and are deleted the first time a node spills; a process ID is never relied on, as it
 * is the same (1) in every container.
 *
 * <p>Every file is encrypted (AES in counter mode) with a key made for the node and held only in
 * its memory, so rows written there cannot be read once the node has gone, even from a file a crash
 * left behind. The key is never written anywhere. A digest of what was written is kept in memory
 * too, and checked when the file is read back ({@link In#matches}).
 */
final class SpillFolder implements AutoCloseable {

    /** Folders of spill files are named this, then the process ID that made them (for people reading the folder). */
    static final String PREFIX = "kilnmark-spill-";
    /** The file in a process folder that its process keeps locked. */
    static final String LOCK = ".lock";
    /** A process folder with no lock file is left alone this long: its process may be making it. */
    static final Duration UNLOCKED_GRACE = Duration.ofHours(1);
    private static final int IV_BYTES = 16;
    private static final int CHUNK = 64 * 1024;
    private static Path processFolder;
    private static FileChannel lockChannel;
    private static FileLock lock;

    private final Path folder;
    private final SecretKey key;
    private final SecureRandom random = new SecureRandom();
    private int files;

    private SpillFolder(Path folder, SecretKey key) {
        this.folder = folder;
        this.key = key;
    }

    /** A new folder, and a new key, for one node. */
    static SpillFolder create() throws IOException {
        Path parent = processFolder();
        Path dir = privateFolder(parent, "node-");
        try {
            KeyGenerator g = KeyGenerator.getInstance("AES");
            g.init(256);
            return new SpillFolder(dir, g.generateKey());
        } catch (GeneralSecurityException e) {
            deleteTree(dir);
            throw new IOException("Cannot make a key for temporary files: " + e.getMessage(), e);
        }
    }

    private static synchronized Path processFolder() throws IOException {
        if (processFolder == null || !Files.isDirectory(processFolder) || lock == null || !lock.isValid()) {
            String configured = System.getProperty("kilnmark.spill-dir");
            Path base = Path.of(configured == null || configured.isBlank() ? System.getProperty("java.io.tmpdir")
                : configured);
            Files.createDirectories(base);
            Path dir = privateFolder(base, PREFIX + ProcessHandle.current().pid() + "-");
            lockChannel = FileChannel.open(dir.resolve(LOCK), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
            lock = lockChannel.tryLock();
            processFolder = dir;
            cleanStale(base);
        }
        return processFolder;
    }

    static Path privateFolder(Path parent, String prefix) throws IOException {
        if (FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) {
            return Files.createTempDirectory(parent, prefix,
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
        }
        Path dir = Files.createTempDirectory(parent, prefix);
        ownerOnly(dir);
        return dir;
    }

    /** Where access lists are kept (Windows): the folder's owner only, passed on to what is made in it. */
    static void ownerOnly(Path dir) {
        AclFileAttributeView acl = Files.getFileAttributeView(dir, AclFileAttributeView.class);
        if (acl == null) {
            return;
        }
        try {
            AclEntry owner = AclEntry.newBuilder()
                .setType(AclEntryType.ALLOW)
                .setPrincipal(Files.getOwner(dir))
                .setPermissions(EnumSet.allOf(AclEntryPermission.class))
                .setFlags(AclEntryFlag.FILE_INHERIT, AclEntryFlag.DIRECTORY_INHERIT)
                .build();
            acl.setAcl(List.of(owner));
        } catch (IOException | RuntimeException e) {
            // the folder keeps the temporary folder's access list, the user's own by default
        }
    }

    /**
     * Deletes {@value #PREFIX}* folders whose process has gone: their lock file can be locked. A
     * folder with no lock file is deleted only once it is older than {@link #UNLOCKED_GRACE}.
     */
    static void cleanStale(Path base) {
        try (var dirs = Files.newDirectoryStream(base, PREFIX + "*")) {
            for (Path d : dirs) {
                if (d.equals(processFolder) || !Files.isDirectory(d)) {
                    continue;
                }
                if (stale(d)) {
                    deleteTree(d);
                }
            }
        } catch (IOException | RuntimeException e) {
            // cleaning up is a courtesy: what is left is encrypted with keys that are gone
        }
    }

    /** Whether a process folder's process has gone. */
    static boolean stale(Path d) throws IOException {
        Path lockFile = d.resolve(LOCK);
        if (!Files.exists(lockFile)) {
            return Files.getLastModifiedTime(d).toInstant().isBefore(Instant.now().minus(UNLOCKED_GRACE));
        }
        try (FileChannel ch = FileChannel.open(lockFile, StandardOpenOption.WRITE)) {
            FileLock l = ch.tryLock();
            if (l == null) {
                return false; // another process holds it: it is running
            }
            l.release();
            return true;
        } catch (OverlappingFileLockException e) {
            return false; // this process holds it
        } catch (IOException e) {
            return false; // cannot tell (on Windows a held lock can refuse even the open): leave it
        }
    }

    /** The node's folder. */
    Path path() {
        return folder;
    }

    /** A new, empty file in the folder. */
    Path newFile(String prefix) throws IOException {
        files++;
        return folder.resolve(prefix + "-" + files + ".arrow");
    }

    /** Writes encrypted to {@code file}, which is created; {@link Out#digest} once it is closed. */
    Out write(Path file) throws IOException {
        OutputStream raw = Files.newOutputStream(file, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        try {
            byte[] iv = new byte[IV_BYTES];
            random.nextBytes(iv);
            raw.write(iv);
            MessageDigest digest = sha256();
            return new Out(new BufferedOutputStream(new DigestOutputStream(
                new Encrypting(raw, cipher(Cipher.ENCRYPT_MODE, iv)), digest), 4 * CHUNK), digest);
        } catch (IOException | RuntimeException e) {
            raw.close();
            throw e;
        }
    }

    /** Reads {@code file} as {@link #write} wrote it. */
    In read(Path file) throws IOException {
        InputStream raw = Files.newInputStream(file);
        try {
            byte[] iv = raw.readNBytes(IV_BYTES);
            if (iv.length != IV_BYTES) {
                throw new IOException("A temporary file of sorted rows is cut short");
            }
            MessageDigest digest = sha256();
            return new In(new BufferedInputStream(new DigestInputStream(
                new Decrypting(raw, cipher(Cipher.DECRYPT_MODE, iv)), digest), 4 * CHUNK), digest);
        } catch (IOException | RuntimeException e) {
            raw.close();
            throw e;
        }
    }

    private static MessageDigest sha256() throws IOException {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (GeneralSecurityException e) {
            throw new IOException("Cannot check temporary files: " + e.getMessage(), e);
        }
    }

    private Cipher cipher(int mode, byte[] iv) throws IOException {
        try {
            Cipher c = Cipher.getInstance("AES/CTR/NoPadding");
            c.init(mode, key, new IvParameterSpec(iv));
            return c;
        } catch (GeneralSecurityException e) {
            throw new IOException("Cannot encrypt temporary files: " + e.getMessage(), e);
        }
    }

    /** Deletes the folder and everything in it. */
    @Override
    public void close() {
        deleteTree(folder);
    }

    static void deleteTree(Path d) {
        if (d == null || !Files.exists(d)) {
            return;
        }
        try (var all = Files.walk(d)) {
            for (Path p : all.sorted(Comparator.reverseOrder()).toList()) {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException e) {
                    p.toFile().deleteOnExit(); // held open elsewhere (Windows): try again as the process ends
                }
            }
        } catch (IOException | RuntimeException e) {
            d.toFile().deleteOnExit();
        }
    }

    /** What {@link #write} gives: the stream, and once it is closed a digest of what went through it. */
    static final class Out extends FilterOutputStream {
        private final MessageDigest digest;
        private byte[] done;

        Out(OutputStream out, MessageDigest digest) {
            super(out);
            this.digest = digest;
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            out.write(b, off, len);
        }

        @Override
        public void close() throws IOException {
            if (done == null) {
                super.close();
                done = digest.digest();
            }
        }

        /** The digest of everything written; only once closed. */
        byte[] digest() {
            if (done == null) {
                throw new IllegalStateException("not closed yet");
            }
            return done.clone();
        }
    }

    /** What {@link #read} gives: the stream, and whether what was read matches a digest. */
    static final class In extends FilterInputStream {
        private final MessageDigest digest;

        In(InputStream in, MessageDigest digest) {
            super(in);
            this.digest = digest;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            return in.read(b, off, len);
        }

        /** Reads to the end, then whether everything read is what {@code expected} was taken of. */
        boolean matches(byte[] expected) throws IOException {
            byte[] rest = new byte[CHUNK];
            while (in.read(rest, 0, rest.length) >= 0) {
                // drain: every byte goes through the digest
            }
            return MessageDigest.isEqual(digest.digest(), expected);
        }
    }

    /** Encrypts in chunks as it writes. */
    private static final class Encrypting extends FilterOutputStream {
        private final Cipher cipher;
        private final byte[] buffer = new byte[CHUNK];

        Encrypting(OutputStream out, Cipher cipher) {
            super(out);
            this.cipher = cipher;
        }

        @Override
        public void write(int b) throws IOException {
            write(new byte[] {(byte) b}, 0, 1);
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            int done = 0;
            while (done < len) {
                int n = Math.min(CHUNK, len - done);
                try {
                    int m = cipher.update(b, off + done, n, buffer, 0);
                    out.write(buffer, 0, m);
                } catch (GeneralSecurityException e) {
                    throw new IOException("Cannot encrypt a temporary file: " + e.getMessage(), e);
                }
                done += n;
            }
        }
    }

    /** Decrypts in chunks as it reads. */
    private static final class Decrypting extends FilterInputStream {
        private final Cipher cipher;
        private final byte[] buffer = new byte[CHUNK];

        Decrypting(InputStream in, Cipher cipher) {
            super(in);
            this.cipher = cipher;
        }

        @Override
        public int read() throws IOException {
            byte[] one = new byte[1];
            int n = read(one, 0, 1);
            return n < 0 ? -1 : one[0] & 0xFF;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            int n = in.read(buffer, 0, Math.min(len, CHUNK));
            if (n <= 0) {
                return n;
            }
            try {
                return cipher.update(buffer, 0, n, b, off);
            } catch (GeneralSecurityException e) {
                throw new IOException("Cannot decrypt a temporary file: " + e.getMessage(), e);
            }
        }

        @Override
        public long skip(long n) throws IOException {
            byte[] waste = new byte[(int) Math.min(n, CHUNK)];
            long left = n;
            while (left > 0) {
                int r = read(waste, 0, (int) Math.min(left, waste.length));
                if (r < 0) {
                    break;
                }
                left -= r;
            }
            return n - left;
        }

        @Override
        public boolean markSupported() {
            return false;
        }
    }
}

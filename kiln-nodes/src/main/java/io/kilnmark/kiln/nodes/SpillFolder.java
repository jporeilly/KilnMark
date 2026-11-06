package io.kilnmark.kiln.nodes;

import java.io.FilterInputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Comparator;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.IvParameterSpec;

/**
 * Where a sort or a join writes rows that do not fit in its memory limit: a folder of its own that
 * only the server's user can read, inside one folder for the process, under the system property
 * {@code kilnmark.spill-dir} or else {@code java.io.tmpdir}. The node deletes its folder when it
 * closes, whether it finished, failed or was stopped; folders left by a process that is no longer
 * running (a crash) are deleted the first time a node spills.
 *
 * <p>Every file is encrypted (AES in counter mode) with a key made for the node and held only in
 * its memory, so rows written there cannot be read once the node has gone, even from a file a crash
 * left behind. The key is never written anywhere.
 */
final class SpillFolder implements AutoCloseable {

    /** Folders of spill files are named this, then the process ID that made them. */
    static final String PREFIX = "kilnmark-spill-";
    private static final int IV_BYTES = 16;
    private static final int CHUNK = 64 * 1024;
    private static Path processFolder;

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
        if (processFolder == null || !Files.isDirectory(processFolder)) {
            String configured = System.getProperty("kilnmark.spill-dir");
            Path base = Path.of(configured == null || configured.isBlank() ? System.getProperty("java.io.tmpdir")
                : configured);
            Files.createDirectories(base);
            cleanStale(base);
            processFolder = privateFolder(base, PREFIX + ProcessHandle.current().pid() + "-");
            processFolder.toFile().deleteOnExit();
        }
        return processFolder;
    }

    private static Path privateFolder(Path parent, String prefix) throws IOException {
        if (FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) {
            return Files.createTempDirectory(parent, prefix,
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
        }
        return Files.createTempDirectory(parent, prefix); // under the user's own temporary folder on Windows
    }

    /** Deletes {@value #PREFIX}* folders whose process is gone. */
    static void cleanStale(Path base) {
        try (var dirs = Files.newDirectoryStream(base, PREFIX + "*")) {
            for (Path d : dirs) {
                String rest = d.getFileName().toString().substring(PREFIX.length());
                int dash = rest.indexOf('-');
                long pid;
                try {
                    pid = Long.parseLong(dash < 0 ? rest : rest.substring(0, dash));
                } catch (NumberFormatException e) {
                    continue; // not one of ours
                }
                if (pid == ProcessHandle.current().pid()
                    || ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false)) {
                    continue;
                }
                deleteTree(d);
            }
        } catch (IOException | RuntimeException e) {
            // cleaning up is a courtesy: what is left is encrypted with keys that are gone
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

    /** Writes encrypted to {@code file}, which is created. */
    OutputStream write(Path file) throws IOException {
        OutputStream raw = Files.newOutputStream(file, java.nio.file.StandardOpenOption.CREATE_NEW,
            java.nio.file.StandardOpenOption.WRITE);
        try {
            byte[] iv = new byte[IV_BYTES];
            random.nextBytes(iv);
            raw.write(iv);
            return new java.io.BufferedOutputStream(new Encrypting(raw, cipher(Cipher.ENCRYPT_MODE, iv)), 4 * CHUNK);
        } catch (IOException | RuntimeException e) {
            raw.close();
            throw e;
        }
    }

    /** Reads {@code file} as {@link #write} wrote it. */
    InputStream read(Path file) throws IOException {
        InputStream raw = Files.newInputStream(file);
        try {
            byte[] iv = raw.readNBytes(IV_BYTES);
            if (iv.length != IV_BYTES) {
                throw new IOException("A temporary file of sorted rows is cut short");
            }
            return new java.io.BufferedInputStream(new Decrypting(raw, cipher(Cipher.DECRYPT_MODE, iv)), 4 * CHUNK);
        } catch (IOException | RuntimeException e) {
            raw.close();
            throw e;
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

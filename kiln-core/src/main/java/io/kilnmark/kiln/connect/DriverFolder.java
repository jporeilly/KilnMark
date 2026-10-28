package io.kilnmark.kiln.connect;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.SQLException;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The drivers folder: jars the customer installed for connectors whose driver Kilnmark cannot ship
 * ({@link Connector#driver()}), one file per driver, named as Maven Central names it
 * ({@code ojdbc11-23.26.3.0.0.jar}).
 *
 * <ul>
 *   <li>Only the file a connector's driver names is ever read, and only when its SHA-256 is the one
 *       the connector pins: anything else in the folder is ignored, and a file with another
 *       checksum is refused with a message saying so.</li>
 *   <li>The verified bytes are copied to a private folder only this server's user can read, beside
 *       the drivers folder ({@code drivers.loaded}, emptied when the server starts:
 *       {@link #prepare()}), and loaded from there, so the file cannot be swapped between the check
 *       and the load. Once loaded, a driver is used again without reading its file while the file's
 *       size and modification time stay the same; replacing the file only changes what the next
 *       load reads, never what is loaded.</li>
 *   <li>Each driver gets a class loader of its own whose parent is the platform class loader: it
 *       sees the JDK (java.sql and the like), never Kilnmark's classes or other drivers, and nothing
 *       of it is on Kilnmark's class path. Connections are made through its {@link java.sql.Driver}
 *       directly, not {@link java.sql.DriverManager}.</li>
 * </ul>
 */
public final class DriverFolder implements JdbcDrivers {

    /** The largest driver file read: a JDBC driver is a few megabytes. */
    public static final long MAX_BYTES = 200L << 20;

    /** Whether a driver can be used. */
    public enum State {
        /** The file is there and is the expected one. */
        READY,
        /** No file of that name. */
        MISSING,
        /** A file of that name with another checksum. */
        MISMATCH,
        /** The file could not be read. */
        UNREADABLE
    }

    /**
     * A driver's state in the folder.
     *
     * @param file    where its file is, or goes
     * @param sha256  the file's checksum when there is a file, else null
     * @param message what to do, for people; null when ready
     */
    public record Status(State state, Path file, String sha256, String message) {

        public boolean ready() {
            return state == State.READY;
        }
    }

    private final Path dir;
    private final Map<String, java.sql.Driver> loaded = new ConcurrentHashMap<>();
    private final Map<Path, Known> checksums = new ConcurrentHashMap<>();
    private final Path privateCopies;

    /** A file's checksum as last read, valid while its size and modification time stay the same. */
    private record Known(long size, long modified, String sha256) {
    }

    public DriverFolder(Path dir) {
        this.dir = dir.toAbsolutePath().normalize();
        Path parent = this.dir.getParent();
        String name = this.dir.getFileName() == null ? "drivers" : this.dir.getFileName().toString();
        this.privateCopies = (parent == null ? this.dir : parent).resolve(name + ".loaded");
    }

    /**
     * Creates the drivers folder, and empties the private folder loaded copies go to (left over
     * from the last run), so it holds only this run's copies. Called when the server starts;
     * failures are left for the first load to report.
     */
    public void prepare() {
        try {
            Files.createDirectories(dir);
        } catch (IOException | RuntimeException e) {
            // read-only or not allowed: drivers then show as missing, with the folder named
        }
        try {
            if (Files.isSymbolicLink(privateCopies)) {
                Files.delete(privateCopies);
            }
            if (Files.isDirectory(privateCopies)) {
                try (java.util.stream.Stream<Path> walk = Files.walk(privateCopies)) {
                    for (Path p : walk.sorted(java.util.Comparator.reverseOrder()).toList()) {
                        if (!p.equals(privateCopies)) {
                            Files.deleteIfExists(p);
                        }
                    }
                }
            }
            privateCopies();
        } catch (IOException | RuntimeException e) {
            // reported by the first load that needs the folder
        }
    }

    /** The private folder loaded copies go to: {@code drivers.loaded} beside the drivers folder. */
    public Path privateFolder() {
        return privateCopies;
    }

    /**
     * An empty folder only this server's user can write ({@code drivers.loaded/empty}), for drivers
     * that would read settings from a folder the environment chooses (Oracle's {@code TNS_ADMIN}
     * and its {@code ojdbc.properties}); null when it cannot be made.
     */
    @Override
    public Path emptyFolder() {
        try {
            Path empty = privateCopies().resolve("empty");
            if (!Files.isDirectory(empty, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                createPrivate(empty);
            }
            return empty;
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    /** The folder. */
    public Path dir() {
        return dir;
    }

    /** Where {@code spec}'s file is, or goes. */
    public Path file(Connector.Driver spec) {
        return dir.resolve(spec.fileName());
    }

    /** Whether {@code spec}'s driver is installed and is the expected file. */
    public Status status(Connector.Driver spec) {
        Path file = file(spec);
        if (!Files.isRegularFile(file)) {
            return new Status(State.MISSING, file, null, JdbcDrivers.missing(spec, dir.toString()));
        }
        String sha;
        try {
            sha = checksum(file);
        } catch (IOException e) {
            return new Status(State.UNREADABLE, file, null, "Cannot read " + file + ": " + e.getMessage());
        }
        if (sha.equals(TOO_LARGE)) {
            return new Status(State.MISMATCH, file, null, tooLarge(spec, file));
        }
        if (!sha.equals(spec.sha256())) {
            return new Status(State.MISMATCH, file, sha, mismatch(spec, file, sha));
        }
        return new Status(State.READY, file, sha, null);
    }

    private static final String TOO_LARGE = "too large";

    private static String tooLarge(Connector.Driver spec, Path file) {
        return file.getFileName() + " in the drivers folder is larger than 200 MB, so it is not the driver and is not "
            + "used. Replace it with "
            + spec.coordinates() + " " + spec.version() + " as Maven Central publishes it, unchanged";
    }

    private static String mismatch(Connector.Driver spec, Path file, String sha) {
        return file.getFileName() + " in the drivers folder is not the driver Kilnmark expects, so it is not used: its "
            + "SHA-256 is " + sha + ", not "
            + spec.sha256() + ". Replace it with " + spec.coordinates() + " " + spec.version() + " as Maven Central "
            + "publishes it, unchanged";
    }

    /**
     * The driver, loaded from the verified file in a class loader of its own; the same instance
     * while the file is unchanged.
     */
    @Override
    public java.sql.Driver driver(Connector.Driver spec) throws SQLException {
        if (spec.driverClass() == null) {
            throw new SQLException(spec.coordinates() + " is not a JDBC driver", "08001");
        }
        Path file = file(spec);
        byte[] bytes;
        long size;
        long modified;
        try {
            java.nio.file.attribute.BasicFileAttributes a = Files.readAttributes(file,
                java.nio.file.attribute.BasicFileAttributes.class);
            if (!a.isRegularFile()) {
                throw new SQLException(JdbcDrivers.missing(spec, null), "08001");
            }
            size = a.size();
            modified = a.lastModifiedTime().toMillis();
            if (size > MAX_BYTES) {
                throw new SQLException(tooLarge(spec, file), "08001");
            }
            // unchanged since it was verified and loaded: the loaded driver, without reading the file again
            Known k = checksums.get(file);
            if (k != null && k.size() == size && k.modified() == modified && k.sha256().equals(spec.sha256())) {
                java.sql.Driver d = loaded.get(k.sha256());
                if (d != null) {
                    return d;
                }
            }
            bytes = Files.readAllBytes(file);
        } catch (NoSuchFileException e) {
            throw new SQLException(JdbcDrivers.missing(spec, null), "08001", e);
        } catch (IOException e) {
            throw new SQLException("Cannot read the driver " + spec.fileName() + " in the drivers folder: "
                + e.getMessage(), "08001", e);
        }
        String sha = sha256(bytes);
        checksums.put(file, new Known(size, modified, sha));
        if (!sha.equals(spec.sha256())) {
            throw new SQLException(mismatch(spec, file, sha), "08001");
        }
        java.sql.Driver d = loaded.get(sha);
        if (d != null) {
            return d;
        }
        synchronized (this) {
            d = loaded.get(sha);
            if (d == null) {
                d = load(spec, bytes);
                loaded.put(sha, d);
            }
            return d;
        }
    }

    /** Loads the driver class from a private copy of {@code bytes}, in a class loader that sees only the JDK. */
    private java.sql.Driver load(Connector.Driver spec, byte[] bytes) throws SQLException {
        try {
            Path copy = privateCopies().resolve(spec.sha256() + ".jar");
            if (!Files.exists(copy)) {
                Path part = Files.createTempFile(privateCopies(), "driver", ".part");
                Files.write(part, bytes);
                Files.move(part, copy, StandardCopyOption.REPLACE_EXISTING);
            }
            // Checked again: the copy is ours, but a second look costs little.
            if (!sha256(Files.readAllBytes(copy)).equals(spec.sha256())) {
                throw new SQLException("The private copy of " + spec.fileName() + " changed", "08001");
            }
            URLClassLoader loader = new URLClassLoader("driver " + spec.coordinates() + " " + spec.version(),
                new URL[] {copy.toUri().toURL()}, ClassLoader.getPlatformClassLoader());
            Class<?> c = Class.forName(spec.driverClass(), true, loader);
            if (!java.sql.Driver.class.isAssignableFrom(c)) {
                throw new SQLException(spec.driverClass() + " in " + spec.fileName() + " is not a JDBC driver", "08001");
            }
            return (java.sql.Driver) c.getDeclaredConstructor().newInstance();
        } catch (IOException | ReflectiveOperationException | LinkageError e) {
            throw new SQLException("Cannot load the driver " + spec.fileName() + ": " + e, "08001", e);
        }
    }

    /** The private folder, made (owner only) when it is not there. */
    private synchronized Path privateCopies() throws IOException {
        if (Files.isSymbolicLink(privateCopies)) {
            throw new IOException(privateCopies + " is a link, not a folder: remove it");
        }
        if (!Files.isDirectory(privateCopies)) {
            createPrivate(privateCopies);
        }
        return privateCopies;
    }

    /** A folder only this server's user may use (where permissions are POSIX ones). */
    private static void createPrivate(Path folder) throws IOException {
        try {
            Files.createDirectories(folder.getParent());
            Files.createDirectory(folder,
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
        } catch (UnsupportedOperationException e) {
            Files.createDirectories(folder); // not POSIX (Windows): the folder's inherited permissions
        } catch (java.nio.file.FileAlreadyExistsException e) {
            // made meanwhile
        }
        try {
            Files.setPosixFilePermissions(folder, PosixFilePermissions.fromString("rwx------"));
        } catch (UnsupportedOperationException e) {
            // not POSIX
        }
    }

    /**
     * Installs {@code spec}'s driver from {@code in} (a download): written beside the target under a
     * temporary name, checked, then moved into place. A stream that is not the expected file, or
     * is larger than {@link #MAX_BYTES}, is refused and leaves nothing behind.
     *
     * @return the installed file
     */
    public Path install(Connector.Driver spec, InputStream in) throws IOException {
        Files.createDirectories(dir);
        Path part = Files.createTempFile(dir, "." + spec.artifact() + "-", ".part");
        try {
            MessageDigest md = digest();
            long total = 0;
            try (OutputStream out = Files.newOutputStream(part)) {
                byte[] buffer = new byte[64 * 1024];
                for (int n; (n = in.read(buffer)) > 0; ) {
                    total += n;
                    if (total > MAX_BYTES) {
                        throw new IOException("The download of " + spec.fileName() + " is larger than 200 MB, so it is "
                            + "not the driver; nothing was installed");
                    }
                    md.update(buffer, 0, n);
                    out.write(buffer, 0, n);
                }
            }
            String sha = HexFormat.of().formatHex(md.digest());
            if (!sha.equals(spec.sha256())) {
                throw new IOException("The downloaded " + spec.fileName() + " is not the expected file (SHA-256 " + sha
                    + ", not " + spec.sha256() + "), so it was not installed. Check the server's Maven mirror");
            }
            Path target = file(spec);
            try {
                Files.move(part, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(part, target, StandardCopyOption.REPLACE_EXISTING);
            }
            return target;
        } finally {
            Files.deleteIfExists(part);
        }
    }

    private String checksum(Path file) throws IOException {
        long size = Files.size(file);
        long modified = Files.getLastModifiedTime(file).toMillis();
        Known k = checksums.get(file);
        if (k != null && k.size() == size && k.modified() == modified) {
            return k.sha256();
        }
        if (size > MAX_BYTES) {
            return TOO_LARGE;
        }
        MessageDigest md = digest();
        try (InputStream in = Files.newInputStream(file)) {
            byte[] buffer = new byte[64 * 1024];
            for (int n; (n = in.read(buffer)) > 0; ) {
                md.update(buffer, 0, n);
            }
        }
        String sha = HexFormat.of().formatHex(md.digest());
        checksums.put(file, new Known(size, modified, sha));
        return sha;
    }

    /** The SHA-256 of {@code bytes}, lower-case hex. */
    public static String sha256(byte[] bytes) {
        return HexFormat.of().formatHex(digest().digest(bytes));
    }

    private static MessageDigest digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is part of every Java runtime", e);
        }
    }
}

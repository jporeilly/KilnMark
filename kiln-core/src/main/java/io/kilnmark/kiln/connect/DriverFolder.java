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

    // ------------------------------------------------------------------ registered drivers

    /**
     * Drivers an admin registered for the generic JDBC connector, by name. The server keeps them in
     * its database (who registered each and when) and hands them here at start and on each change;
     * this folder only loads them.
     */
    private final Map<String, RegisteredDriver> registrations = new ConcurrentHashMap<>();
    /** Registered drivers loaded, by checksum and class: a jar registered twice is loaded once per class. */
    private final Map<String, java.sql.Driver> registeredLoaded = new ConcurrentHashMap<>();

    /** A jar in the folder, as the registration screen lists it: nothing is read from it. */
    public record FolderFile(String fileName, long size, java.time.Instant modified) {
    }

    /** A jar read and loaded for registration: its checksum and size, and the driver class it holds. */
    public record Inspected(String fileName, String sha256, long size, int majorVersion, int minorVersion) {
    }

    /** Replaces the registered drivers with {@code all} (the server's records, at start). */
    public void registered(java.util.Collection<RegisteredDriver> all) {
        registrations.clear();
        all.forEach(r -> registrations.put(r.name(), r));
    }

    /** Adds or replaces one registration. */
    public void register(RegisteredDriver r) {
        registrations.put(r.name(), r);
    }

    /** Removes the registration called {@code name}; its loaded driver stays loaded but is never handed out again. */
    public void unregister(String name) {
        registrations.remove(name);
    }

    @Override
    public java.util.List<RegisteredDriver> registered() {
        return registrations.values().stream().sorted(java.util.Comparator.comparing(RegisteredDriver::name)).toList();
    }

    @Override
    public java.util.Optional<RegisteredDriver> registration(String name) {
        return java.util.Optional.ofNullable(name == null ? null : registrations.get(name));
    }

    /**
     * The file called {@code fileName} directly in the folder: a bare name of a jar, never a path, so
     * nothing outside the folder can be named.
     *
     * @throws IOException saying what is wrong with the name
     */
    public Path jar(String fileName) throws IOException {
        java.util.List<String> problems = RegisteredDriver.problems("x", fileName, "x", "jdbc:x:");
        if (!problems.isEmpty()) {
            throw new IOException(problems.get(0));
        }
        Path file = dir.resolve(fileName).normalize();
        if (!dir.equals(file.getParent())) {
            throw new IOException("fileName: a jar directly in the drivers folder");
        }
        return file;
    }

    /**
     * The jars in the folder, by name, without reading them: the files an admin may register. Links,
     * folders and files whose names a registration cannot hold are left out.
     */
    public java.util.List<FolderFile> jars() {
        java.util.List<FolderFile> out = new java.util.ArrayList<>();
        try (java.util.stream.Stream<Path> files = Files.list(dir)) {
            for (Path f : files.sorted().toList()) {
                String name = f.getFileName().toString();
                if (!RegisteredDriver.problems("x", name, "x", "jdbc:x:").isEmpty()) {
                    continue;
                }
                java.nio.file.attribute.BasicFileAttributes a = Files.readAttributes(f,
                    java.nio.file.attribute.BasicFileAttributes.class, java.nio.file.LinkOption.NOFOLLOW_LINKS);
                if (a.isRegularFile()) {
                    out.add(new FolderFile(name, a.size(), a.lastModifiedTime().toInstant()));
                }
            }
        } catch (IOException | RuntimeException e) {
            // no folder (yet), or not readable: nothing to offer
        }
        return out;
    }

    /**
     * Puts an uploaded jar in the folder under {@code fileName}: written beside the target under a
     * temporary name, at most {@link #MAX_BYTES}, then moved into place. A file of that name already
     * there is never replaced (a registered jar must not change under its registration).
     *
     * @return the file's SHA-256
     */
    public String upload(String fileName, InputStream in) throws IOException {
        Path target = jar(fileName);
        Files.createDirectories(dir);
        if (Files.exists(target, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            throw new java.nio.file.FileAlreadyExistsException(fileName + " is already in the drivers folder: register "
                + "that file, or give the new one another name");
        }
        Path part = Files.createTempFile(dir, ".upload-", ".part");
        try {
            MessageDigest md = digest();
            long total = 0;
            try (OutputStream out = Files.newOutputStream(part)) {
                byte[] buffer = new byte[64 * 1024];
                for (int n; (n = in.read(buffer)) > 0; ) {
                    total += n;
                    if (total > MAX_BYTES) {
                        throw new IOException(fileName + " is larger than 200 MB, so it is not a JDBC driver; nothing "
                            + "was saved");
                    }
                    md.update(buffer, 0, n);
                    out.write(buffer, 0, n);
                }
            }
            if (total == 0) {
                throw new IOException(fileName + " is empty; nothing was saved");
            }
            // A new link to the written file fails if the name is taken, in one step, so a file put there
            // meanwhile is never overwritten; where links are not supported, a move that never replaces.
            try {
                Files.createLink(target, part);
            } catch (UnsupportedOperationException e) {
                Files.move(part, target);
            }
            return HexFormat.of().formatHex(md.digest());
        } finally {
            Files.deleteIfExists(part);
        }
    }

    /**
     * Reads {@code fileName} from the folder and loads {@code driverClass} from it, as registering
     * does, so an admin learns at once whether the jar holds that driver. The driver is loaded the
     * way {@link #driver(RegisteredDriver)} loads it, from a private copy, in a class loader of its
     * own; that instance is used when the registration is.
     *
     * @throws IOException  when the file cannot be read or is too large
     * @throws SQLException when the class is not in the jar or is not a JDBC driver
     */
    public Inspected inspect(String fileName, String driverClass) throws IOException, SQLException {
        Path file = jar(fileName);
        java.nio.file.attribute.BasicFileAttributes a = Files.readAttributes(file,
            java.nio.file.attribute.BasicFileAttributes.class, java.nio.file.LinkOption.NOFOLLOW_LINKS);
        if (!a.isRegularFile()) {
            throw new IOException(fileName + " in the drivers folder is not a plain file");
        }
        if (a.size() > MAX_BYTES) {
            throw new IOException(fileName + " is larger than 200 MB, so it is not a JDBC driver");
        }
        byte[] bytes = readBounded(file, fileName);
        String sha = sha256(bytes);
        java.sql.Driver d = loadRegistered(fileName, sha, driverClass, bytes);
        return new Inspected(fileName, sha, bytes.length, d.getMajorVersion(), d.getMinorVersion());
    }

    /** Whether a registered driver's jar is there and still the file that was registered. */
    public Status status(RegisteredDriver r) {
        Path file;
        try {
            file = jar(r.fileName());
        } catch (IOException e) {
            return new Status(State.UNREADABLE, dir, null, e.getMessage());
        }
        if (!Files.isRegularFile(file, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            return new Status(State.MISSING, file, null, r.fileName() + ", registered as the driver " + r.name()
                + ", is not in the drivers folder " + dir + " any more. Put it back, unchanged, or remove the "
                + "registration");
        }
        String sha;
        try {
            sha = checksum(file);
        } catch (IOException e) {
            return new Status(State.UNREADABLE, file, null, "Cannot read " + file + ": " + e.getMessage());
        }
        if (!sha.equals(r.sha256())) {
            return new Status(State.MISMATCH, file, sha.equals(TOO_LARGE) ? null : sha, changed(r, sha));
        }
        return new Status(State.READY, file, sha, null);
    }

    private static String changed(RegisteredDriver r, String sha) {
        return r.fileName() + " has changed since it was registered as the driver " + r.name() + ", so it is not used: "
            + (sha.equals(TOO_LARGE) ? "it is now larger than 200 MB" : "its SHA-256 is now " + sha) + ", not "
            + r.sha256() + ". Put the registered file back, or remove the registration and register the new file";
    }

    /**
     * The registered driver, loaded from its jar only while the jar's SHA-256 is the one recorded
     * at registration, in a class loader of its own; the same instance while the file is unchanged.
     * Never registered with {@link java.sql.DriverManager}.
     */
    @Override
    public java.sql.Driver driver(RegisteredDriver r) throws SQLException {
        RegisteredDriver current = registrations.get(r.name());
        if (current == null || !current.equals(r)) {
            throw new SQLException(JdbcDrivers.notRegistered(r.name()), "08001");
        }
        Path file;
        byte[] bytes;
        long size;
        long modified;
        try {
            file = jar(r.fileName());
            java.nio.file.attribute.BasicFileAttributes a = Files.readAttributes(file,
                java.nio.file.attribute.BasicFileAttributes.class, java.nio.file.LinkOption.NOFOLLOW_LINKS);
            if (!a.isRegularFile()) {
                throw new NoSuchFileException(file.toString());
            }
            size = a.size();
            modified = a.lastModifiedTime().toMillis();
            if (size > MAX_BYTES) {
                throw new SQLException(changed(r, TOO_LARGE), "08001");
            }
            Known k = checksums.get(file);
            if (k != null && k.size() == size && k.modified() == modified && k.sha256().equals(r.sha256())) {
                java.sql.Driver d = registeredLoaded.get(r.sha256() + "/" + r.driverClass());
                if (d != null) {
                    return d;
                }
            }
            bytes = readBounded(file, r.fileName());
        } catch (NoSuchFileException e) {
            throw new SQLException("The JDBC driver " + r.name() + " (" + r.fileName() + ") is not in the drivers "
                + "folder any more. An admin puts it back, unchanged, or registers another under Settings, JDBC "
                + "drivers", "08001", e);
        } catch (IOException e) {
            throw new SQLException("Cannot read the JDBC driver " + r.fileName() + " in the drivers folder: "
                + e.getMessage(), "08001", e);
        }
        String sha = sha256(bytes);
        checksums.put(file, new Known(size, modified, sha));
        if (!sha.equals(r.sha256())) {
            throw new SQLException(changed(r, sha), "08001");
        }
        return loadRegistered(r.fileName(), sha, r.driverClass(), bytes);
    }

    /** A registered driver's class from a private copy of {@code bytes}, loaded once per checksum and class. */
    private java.sql.Driver loadRegistered(String fileName, String sha, String driverClass, byte[] bytes)
        throws SQLException {
        String key = sha + "/" + driverClass;
        java.sql.Driver d = registeredLoaded.get(key);
        if (d != null) {
            return d;
        }
        synchronized (this) {
            d = registeredLoaded.get(key);
            if (d != null) {
                return d;
            }
            try {
                Path copy = privateCopies().resolve(sha + ".jar");
                if (!Files.exists(copy)) {
                    Path part = Files.createTempFile(privateCopies(), "driver", ".part");
                    Files.write(part, bytes);
                    Files.move(part, copy, StandardCopyOption.REPLACE_EXISTING);
                }
                if (!sha256(Files.readAllBytes(copy)).equals(sha)) {
                    throw new SQLException("The private copy of " + fileName + " changed", "08001");
                }
                URLClassLoader loader = new URLClassLoader("registered driver " + fileName,
                    new URL[] {copy.toUri().toURL()}, ClassLoader.getPlatformClassLoader());
                Class<?> c;
                try {
                    // not initialised yet: no code of the jar runs before it is known to be a driver
                    c = Class.forName(driverClass, false, loader);
                } catch (ClassNotFoundException e) {
                    throw new SQLException(fileName + " holds no class " + driverClass + ". Check the driver's class "
                        + "name in its documentation", "08001", e);
                }
                if (!java.sql.Driver.class.isAssignableFrom(c)) {
                    throw new SQLException(driverClass + " in " + fileName + " is not a JDBC driver (it does not "
                        + "implement java.sql.Driver)", "08001");
                }
                c = Class.forName(driverClass, true, loader);
                d = (java.sql.Driver) c.getDeclaredConstructor().newInstance();
            } catch (IOException | ReflectiveOperationException | LinkageError e) {
                throw new SQLException("Cannot load " + driverClass + " from " + fileName + ": " + e, "08001", e);
            }
            registeredLoaded.put(key, d);
            return d;
        }
    }

    /**
     * A registered jar's bytes, read without following a link and never more than {@link #MAX_BYTES}:
     * a file that grows past the limit while it is read is refused.
     */
    private static byte[] readBounded(Path file, String fileName) throws IOException {
        try (InputStream in = Files.newInputStream(file, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            byte[] bytes = in.readNBytes((int) MAX_BYTES + 1);
            if (bytes.length > MAX_BYTES) {
                throw new IOException(fileName + " is larger than 200 MB, so it is not a JDBC driver");
            }
            return bytes;
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

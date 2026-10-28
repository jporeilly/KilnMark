package io.kilnmark.kiln.connect;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The drivers folder: checksums, isolation, and what is said when a driver is missing or wrong. */
class DriverFolderTest {

    /** A jar holding a tiny JDBC driver, compiled for the test. */
    private static byte[] driverJar;
    /** Another jar in the folder, which must never be loaded. */
    private static byte[] otherJar;

    @TempDir
    Path root;

    /** The drivers folder, inside the test's folder, so the private copies beside it are too. */
    Path dir;

    @org.junit.jupiter.api.BeforeEach
    void folder() throws IOException {
        dir = Files.createDirectories(root.resolve("drivers"));
    }

    @BeforeAll
    static void compile(@TempDir Path work) throws IOException {
        driverJar = jar(work.resolve("d"), "fake.FakeDriver", """
            package fake;
            public class FakeDriver implements java.sql.Driver {
                public java.sql.Connection connect(String url, java.util.Properties info) { return null; }
                public boolean acceptsURL(String url) { return url.startsWith("jdbc:fake:"); }
                public java.sql.DriverPropertyInfo[] getPropertyInfo(String u, java.util.Properties i) {
                    return new java.sql.DriverPropertyInfo[0];
                }
                public int getMajorVersion() { return 1; }
                public int getMinorVersion() { return 0; }
                public boolean jdbcCompliant() { return false; }
                public java.util.logging.Logger getParentLogger() { return null; }
                /** Whether this driver's loader can see a class: Kilnmark's must stay out of sight. */
                public static boolean sees(String name) {
                    try {
                        Class.forName(name, false, FakeDriver.class.getClassLoader());
                        return true;
                    } catch (ClassNotFoundException e) {
                        return false;
                    }
                }
            }
            """);
        otherJar = jar(work.resolve("o"), "other.Other", "package other; public class Other {}");
    }

    private static byte[] jar(Path work, String className, String source) throws IOException {
        Path src = work.resolve("src/" + className.replace('.', '/') + ".java");
        Files.createDirectories(src.getParent());
        Files.writeString(src, source);
        Path out = Files.createDirectories(work.resolve("classes"));
        JavaCompiler javac = ToolProvider.getSystemJavaCompiler();
        int rc = javac.run(null, null, null, "-d", out.toString(), src.toString());
        assertEquals(0, rc, "the test driver compiles");
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        try (JarOutputStream jar = new JarOutputStream(b)) {
            String entry = className.replace('.', '/') + ".class";
            jar.putNextEntry(new JarEntry(entry));
            jar.write(Files.readAllBytes(out.resolve(entry)));
            jar.closeEntry();
        }
        return b.toByteArray();
    }

    private static Connector.Driver spec(byte[] jar) {
        return new Connector.Driver("com.example.test:fake-jdbc", "1.2.3", DriverFolder.sha256(jar),
            "Example licence", "https://licences.example.com/fake", false, "fake.FakeDriver");
    }

    @Test
    void aMissingDriverSaysWhereItGoesAndWhoInstallsIt() {
        DriverFolder folder = new DriverFolder(dir);
        Connector.Driver spec = spec(driverJar);
        DriverFolder.Status s = folder.status(spec);
        assertEquals(DriverFolder.State.MISSING, s.state());
        assertEquals(dir.resolve("fake-jdbc-1.2.3.jar"), s.file());
        assertTrue(s.message().contains("Settings, Drivers"), s.message());
        assertTrue(s.message().contains("put fake-jdbc-1.2.3.jar in " + dir), s.message());
        SQLException e = assertThrows(SQLException.class, () -> folder.driver(spec));
        assertEquals("08001", e.getSQLState());
        assertTrue(e.getMessage().contains("needs its driver, com.example.test:fake-jdbc 1.2.3"), e.getMessage());
        SQLException none = assertThrows(SQLException.class, () -> JdbcDrivers.NONE.driver(spec));
        assertTrue(none.getMessage().contains("Settings, Drivers"), none.getMessage());
    }

    @Test
    void aFileWithAnotherChecksumIsRefusedAndNeverLoaded() throws Exception {
        DriverFolder folder = new DriverFolder(dir);
        Connector.Driver spec = spec(driverJar);
        byte[] tampered = driverJar.clone();
        tampered[tampered.length - 1] ^= 1;
        Files.write(folder.file(spec), tampered);
        DriverFolder.Status s = folder.status(spec);
        assertEquals(DriverFolder.State.MISMATCH, s.state());
        assertEquals(DriverFolder.sha256(tampered), s.sha256());
        assertTrue(s.message().contains("is not the driver Kilnmark expects"), s.message());
        assertTrue(s.message().contains("not " + spec.sha256()), s.message());
        SQLException e = assertThrows(SQLException.class, () -> folder.driver(spec));
        assertTrue(e.getMessage().contains("its SHA-256 is " + DriverFolder.sha256(tampered)), e.getMessage());
    }

    @Test
    void theVerifiedDriverLoadsAloneInItsOwnClassLoader() throws Exception {
        DriverFolder folder = new DriverFolder(dir);
        Connector.Driver spec = spec(driverJar);
        Files.write(folder.file(spec), driverJar);
        Files.write(dir.resolve("other-1.0.jar"), otherJar);
        assertEquals(DriverFolder.State.READY, folder.status(spec).state());

        java.sql.Driver d = folder.driver(spec);
        assertEquals("fake.FakeDriver", d.getClass().getName());
        assertTrue(d.acceptsURL("jdbc:fake:x"));
        ClassLoader loader = d.getClass().getClassLoader();
        assertNotSame(getClass().getClassLoader(), loader);
        assertSame(ClassLoader.getPlatformClassLoader(), loader.getParent());
        // the driver sees the JDK, but neither Kilnmark nor anything else in the folder
        boolean seesJdk = (Boolean) d.getClass().getMethod("sees", String.class).invoke(null, "java.sql.Driver");
        boolean seesKilnmark = (Boolean) d.getClass().getMethod("sees", String.class)
            .invoke(null, Connector.class.getName());
        boolean seesOther = (Boolean) d.getClass().getMethod("sees", String.class).invoke(null, "other.Other");
        assertTrue(seesJdk);
        assertFalse(seesKilnmark, "Kilnmark's classes are out of the driver's sight");
        assertFalse(seesOther, "other files in the folder are never loaded");
        // not reachable through DriverManager from Kilnmark's own class loader
        assertThrows(SQLException.class, () -> java.sql.DriverManager.getDriver("jdbc:fake:x"));
        assertSame(d, folder.driver(spec), "loaded once while the file is unchanged");
    }

    @Test
    void aLoadedDriverIsUsedAgainWithoutReadingItsFileWhileTheFileIsUnchanged() throws Exception {
        DriverFolder folder = new DriverFolder(dir);
        Connector.Driver spec = spec(driverJar);
        Path file = folder.file(spec);
        Files.write(file, driverJar);
        java.sql.Driver d = folder.driver(spec);
        java.nio.file.attribute.FileTime modified = Files.getLastModifiedTime(file);

        // same size and modification time: not read again (these bytes would fail the checksum)
        byte[] garbage = new byte[driverJar.length];
        Files.write(file, garbage);
        Files.setLastModifiedTime(file, modified);
        assertSame(d, folder.driver(spec), "the loaded driver, from its verified private copy");

        // a new modification time: read and checked again, and refused
        Files.setLastModifiedTime(file, java.nio.file.attribute.FileTime.fromMillis(modified.toMillis() + 5000));
        SQLException e = assertThrows(SQLException.class, () -> folder.driver(spec));
        assertTrue(e.getMessage().contains("is not the driver Kilnmark expects"), e.getMessage());
    }

    @Test
    void loadedCopiesLiveInOnePrivateFolderBesideTheDriversFolderClearedAtStart() throws Exception {
        DriverFolder folder = new DriverFolder(dir);
        Connector.Driver spec = spec(driverJar);
        Files.write(folder.file(spec), driverJar);
        folder.driver(spec);
        Path copies = folder.privateFolder();
        assertEquals(root.resolve("drivers.loaded"), copies);
        assertTrue(Files.exists(copies.resolve(spec.sha256() + ".jar")), listing(copies).toString());
        if (copies.getFileSystem().supportedFileAttributeViews().contains("posix")) {
            assertEquals("rwx------", java.nio.file.attribute.PosixFilePermissions.toString(
                Files.getPosixFilePermissions(copies)));
        }
        Path empty = folder.emptyFolder();
        assertEquals(copies.resolve("empty"), empty);
        assertEquals(List.of(), listing(empty), "nothing for a driver to read settings from");

        Files.writeString(copies.resolve("left-over.jar"), "from the last run");
        new DriverFolder(dir).prepare();
        assertEquals(List.of(), listing(copies), "emptied when the server starts");
        assertTrue(Files.isDirectory(copies));
    }

    @Test
    void installKeepsOnlyTheExpectedFile() throws Exception {
        DriverFolder folder = new DriverFolder(dir.resolve("drivers"));
        Connector.Driver spec = spec(driverJar);
        IOException wrong = assertThrows(IOException.class,
            () -> folder.install(spec, new ByteArrayInputStream("not a jar".getBytes(StandardCharsets.UTF_8))));
        assertTrue(wrong.getMessage().contains("is not the expected file"), wrong.getMessage());
        assertEquals(List.of(), listing(folder.dir()), "nothing is left behind");

        Path installed = folder.install(spec, new ByteArrayInputStream(driverJar));
        assertEquals(folder.file(spec), installed);
        assertEquals(List.of("fake-jdbc-1.2.3.jar"), listing(folder.dir()));
        assertTrue(folder.status(spec).ready());
    }

    @Test
    void aDriverNeedsCoordinatesAVersionAndAChecksum() {
        assertThrows(IllegalArgumentException.class,
            () -> new Connector.Driver("no-colon", "1", "a".repeat(64), "l", "u", false));
        assertThrows(IllegalArgumentException.class,
            () -> new Connector.Driver("g:a", "../1", "a".repeat(64), "l", "u", false));
        assertThrows(IllegalArgumentException.class, () -> new Connector.Driver("g:a", "1", "ABC", "l", "u", false));
        Connector.Driver d = new Connector.Driver("com.oracle.database.jdbc:ojdbc11", "23.26.3.0.0", "a".repeat(64),
            "FUTC", "https://www.oracle.com/downloads/licenses/oracle-free-license.html", false,
            "oracle.jdbc.OracleDriver");
        assertEquals("com/oracle/database/jdbc/ojdbc11/23.26.3.0.0/ojdbc11-23.26.3.0.0.jar", d.repositoryPath());
    }

    private static List<String> listing(Path dir) throws IOException {
        try (var s = Files.list(dir)) {
            return s.map(p -> p.getFileName().toString()).sorted().toList();
        }
    }
}

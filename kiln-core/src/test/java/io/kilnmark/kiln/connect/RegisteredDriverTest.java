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
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Drivers an admin registers for the generic JDBC connector: checked and hashed at registration,
 * loaded only while the hash matches, isolated like pinned drivers, and never from outside the folder.
 */
class RegisteredDriverTest {

    private static byte[] driverJar;
    private static byte[] notADriverJar;

    @TempDir
    Path root;
    Path dir;

    /** Every folder a test opens, released after it: a loaded driver's jar stays open until then. */
    private final java.util.List<DriverFolder> opened = new java.util.ArrayList<>();

    private DriverFolder open(Path d) {
        DriverFolder f = new DriverFolder(d);
        opened.add(f);
        return f;
    }

    @org.junit.jupiter.api.AfterEach
    void releaseDrivers() {
        opened.forEach(DriverFolder::close);
    }

    @BeforeEach
    void folder() throws IOException {
        dir = Files.createDirectories(root.resolve("drivers"));
    }

    @BeforeAll
    static void compile(@TempDir Path work) throws IOException {
        driverJar = jar(work.resolve("d"), "acme.AcmeDriver", """
            package acme;
            public class AcmeDriver implements java.sql.Driver {
                public java.sql.Connection connect(String url, java.util.Properties info) { return null; }
                public boolean acceptsURL(String url) { return url.startsWith("jdbc:acme:"); }
                public java.sql.DriverPropertyInfo[] getPropertyInfo(String u, java.util.Properties i) {
                    return new java.sql.DriverPropertyInfo[0];
                }
                public int getMajorVersion() { return 4; }
                public int getMinorVersion() { return 2; }
                public boolean jdbcCompliant() { return false; }
                public java.util.logging.Logger getParentLogger() { return null; }
                public static boolean sees(String name) {
                    try {
                        Class.forName(name, false, AcmeDriver.class.getClassLoader());
                        return true;
                    } catch (ClassNotFoundException e) {
                        return false;
                    }
                }
            }
            """);
        notADriverJar = jar(work.resolve("o"), "acme.Helper", "package acme; public class Helper {}");
    }

    private static byte[] jar(Path work, String className, String source) throws IOException {
        Path src = work.resolve("src/" + className.replace('.', '/') + ".java");
        Files.createDirectories(src.getParent());
        Files.writeString(src, source);
        Path out = Files.createDirectories(work.resolve("classes"));
        JavaCompiler javac = ToolProvider.getSystemJavaCompiler();
        assertEquals(0, javac.run(null, null, null, "-d", out.toString(), src.toString()));
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        try (JarOutputStream jar = new JarOutputStream(b)) {
            String entry = className.replace('.', '/') + ".class";
            jar.putNextEntry(new JarEntry(entry));
            jar.write(Files.readAllBytes(out.resolve(entry)));
            jar.closeEntry();
        }
        return b.toByteArray();
    }

    private RegisteredDriver registered(DriverFolder folder, String file) throws Exception {
        DriverFolder.Inspected i = folder.inspect(file, "acme.AcmeDriver");
        RegisteredDriver r = new RegisteredDriver("acme", file, i.sha256(), "acme.AcmeDriver", "jdbc:acme:",
            List.of("currentschema"), "admin@example.com", Instant.parse("2026-10-28T10:00:00Z"));
        folder.register(r);
        return r;
    }

    @Test
    void registrationRecordsTheChecksumAndChecksTheClass() throws Exception {
        DriverFolder folder = open(dir);
        Files.write(dir.resolve("acme-4.2.jar"), driverJar);
        DriverFolder.Inspected i = folder.inspect("acme-4.2.jar", "acme.AcmeDriver");
        assertEquals(DriverFolder.sha256(driverJar), i.sha256());
        assertEquals(4, i.majorVersion());
        assertEquals(2, i.minorVersion());

        SQLException missing = assertThrows(SQLException.class, () -> folder.inspect("acme-4.2.jar", "acme.Nope"));
        assertTrue(missing.getMessage().contains("holds no class acme.Nope"), missing.getMessage());
        Files.write(dir.resolve("helper.jar"), notADriverJar);
        SQLException notDriver = assertThrows(SQLException.class, () -> folder.inspect("helper.jar", "acme.Helper"));
        assertTrue(notDriver.getMessage().contains("is not a JDBC driver"), notDriver.getMessage());
        assertThrows(java.nio.file.NoSuchFileException.class, () -> folder.inspect("absent.jar", "acme.AcmeDriver"));
    }

    @Test
    void aRegisteredDriverLoadsOnlyWhileItsChecksumMatches() throws Exception {
        DriverFolder folder = open(dir);
        Path file = dir.resolve("acme-4.2.jar");
        Files.write(file, driverJar);
        RegisteredDriver r = registered(folder, "acme-4.2.jar");
        assertEquals(DriverFolder.State.READY, folder.status(r).state());
        java.sql.Driver d = folder.driver(r);
        assertTrue(d.acceptsURL("jdbc:acme:sales"));

        byte[] tampered = driverJar.clone();
        tampered[tampered.length - 1] ^= 1;
        Files.write(file, tampered);
        Files.setLastModifiedTime(file, java.nio.file.attribute.FileTime.from(Instant.now().plusSeconds(10)));
        DriverFolder.Status s = folder.status(r);
        assertEquals(DriverFolder.State.MISMATCH, s.state());
        assertTrue(s.message().contains("has changed since it was registered as the driver acme"), s.message());
        SQLException e = assertThrows(SQLException.class, () -> folder.driver(r));
        assertEquals("08001", e.getSQLState());
        assertTrue(e.getMessage().contains("its SHA-256 is now " + DriverFolder.sha256(tampered)), e.getMessage());

        Files.delete(file);
        assertEquals(DriverFolder.State.MISSING, folder.status(r).state());
        SQLException gone = assertThrows(SQLException.class, () -> folder.driver(r));
        assertTrue(gone.getMessage().contains("is not in the drivers folder any more"), gone.getMessage());
    }

    @Test
    void aRegisteredDriverIsIsolatedLikeAPinnedOne() throws Exception {
        DriverFolder folder = open(dir);
        Files.write(dir.resolve("acme-4.2.jar"), driverJar);
        RegisteredDriver r = registered(folder, "acme-4.2.jar");
        java.sql.Driver d = folder.driver(r);
        ClassLoader loader = d.getClass().getClassLoader();
        assertNotSame(getClass().getClassLoader(), loader);
        assertSame(ClassLoader.getPlatformClassLoader(), loader.getParent());
        assertFalse((Boolean) d.getClass().getMethod("sees", String.class).invoke(null, Connector.class.getName()));
        assertTrue((Boolean) d.getClass().getMethod("sees", String.class).invoke(null, "java.sql.Driver"));
        // never DriverManager-global
        assertThrows(SQLException.class, () -> java.sql.DriverManager.getDriver("jdbc:acme:sales"));
        assertTrue(Files.exists(folder.privateFolder().resolve(r.sha256() + ".jar")), "loaded from a private copy");
        assertSame(d, folder.driver(r), "loaded once while the file is unchanged");
    }

    @Test
    void aRemovedRegistrationIsNeverHandedOut() throws Exception {
        DriverFolder folder = open(dir);
        Files.write(dir.resolve("acme-4.2.jar"), driverJar);
        RegisteredDriver r = registered(folder, "acme-4.2.jar");
        assertEquals(List.of(r), folder.registered());
        assertEquals(r, folder.registration("acme").orElseThrow());
        folder.unregister("acme");
        assertTrue(folder.registered().isEmpty());
        SQLException e = assertThrows(SQLException.class, () -> folder.driver(r));
        assertTrue(e.getMessage().contains("No JDBC driver called acme is registered"), e.getMessage());
        SQLException none = assertThrows(SQLException.class, () -> JdbcDrivers.NONE.driver(r));
        assertTrue(none.getMessage().contains("Settings, JDBC drivers"), none.getMessage());
    }

    @Test
    void onlyJarsDirectlyInTheFolderCanBeNamed() throws Exception {
        DriverFolder folder = open(dir);
        for (String bad : List.of("../acme.jar", "sub/acme.jar", "/etc/passwd", "acme.zip", ".hidden.jar", "a..b.jar")) {
            assertThrows(IOException.class, () -> folder.jar(bad), bad);
        }
        Files.write(dir.resolve("acme-4.2.jar"), driverJar);
        Files.createDirectories(dir.resolve("folder.jar"));
        Files.writeString(dir.resolve("notes.txt"), "not a jar");
        assertEquals(List.of("acme-4.2.jar"), folder.jars().stream().map(DriverFolder.FolderFile::fileName).toList());
    }

    @Test
    void aLinkInTheFolderIsNeverFollowed() throws Exception {
        DriverFolder folder = open(dir);
        Path elsewhere = root.resolve("elsewhere.jar");
        Files.write(elsewhere, driverJar);
        Files.createSymbolicLink(dir.resolve("linked.jar"), elsewhere);
        assertThrows(IOException.class, () -> folder.inspect("linked.jar", "acme.AcmeDriver"));
        assertEquals(List.of(), folder.jars(), "a link is not offered for registering");
    }

    @Test
    void uploadsNeverReplaceAFileAndStayWithinTheLimit() throws Exception {
        DriverFolder folder = open(dir);
        String sha = folder.upload("acme-4.2.jar", new ByteArrayInputStream(driverJar));
        assertEquals(DriverFolder.sha256(driverJar), sha);
        assertThrows(java.nio.file.FileAlreadyExistsException.class,
            () -> folder.upload("acme-4.2.jar", new ByteArrayInputStream(notADriverJar)));
        assertEquals(DriverFolder.sha256(driverJar), DriverFolder.sha256(Files.readAllBytes(dir.resolve("acme-4.2.jar"))));
        assertThrows(IOException.class, () -> folder.upload("empty.jar", new ByteArrayInputStream(new byte[0])));
        assertEquals(List.of("acme-4.2.jar"), folder.jars().stream().map(DriverFolder.FolderFile::fileName).toList(),
            "nothing left behind");
    }

    @Test
    void whatAnAdminTypesIsChecked() {
        assertEquals(List.of(), RegisteredDriver.problems("db2", "jcc-12.1.0.0.jar", "com.ibm.db2.jcc.DB2Driver",
            "jdbc:db2://"));
        assertEquals(4, RegisteredDriver.problems("DB 2", "../x.jar", "not a class", "http://x").size());
        assertThrows(IllegalArgumentException.class, () -> new RegisteredDriver("db2", "jcc.jar", "ABC",
            "com.ibm.db2.jcc.DB2Driver", "jdbc:db2://", List.of(), "a", Instant.EPOCH));
        RegisteredDriver r = new RegisteredDriver("td", "terajdbc.jar", "a".repeat(64), "com.teradata.jdbc.TeraDriver",
            "jdbc:teradata://", RegisteredDriver.propertyList("DATABASE, charset\nTMODE"), "a", Instant.EPOCH);
        assertEquals(List.of("charset", "database", "tmode"), r.allowedProperties());
        assertTrue(r.allows("CHARSET"));
        assertFalse(r.allows("logdata"));
        for (String secret : List.of("password", "sslPassword", "pwd", "apiKey", "token", "private_key_file_pwd",
            "user")) {
            assertEquals(1, RegisteredDriver.propertyProblems(RegisteredDriver.propertyList(secret)).size(), secret);
        }
        assertThrows(IllegalArgumentException.class, () -> new RegisteredDriver("td", "terajdbc.jar", "a".repeat(64),
            "com.teradata.jdbc.TeraDriver", "jdbc:teradata://", List.of("logon password"), "a", Instant.EPOCH));
        assertTrue(r.takes("jdbc:teradata://db.example.com/DATABASE=sales"));
        assertTrue(r.takes("JDBC:Teradata://db.example.com"), "the scheme ignores case");
        assertFalse(r.takes("jdbc:db2://db.example.com:50000/sales"));
    }
}

package io.kilnmark.kiln.plugins.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.kilnmark.kiln.connect.SourceConfig;
import io.kilnmark.kiln.plugins.files.S3Connector;

/** URL properties that would use the server's own identity or files are refused; hosts are found. */
class JdbcUrlRulesTest {

    private static List<String> problems(JdbcConnector c, String url) {
        return c.validate(new SourceConfig("s", c.kind(), Map.of("url", url), null));
    }

    @Test
    void refusesPropertiesThatUseTheServersIdentityOrFiles() {
        PostgresConnector pg = new PostgresConnector();
        for (String bad : List.of("sslkey=/etc/key", "sslcert=/x", "sslrootcert=/x", "sslfactory=a.B",
            "sslpasswordcallback=a.B", "socketFactory=a.B", "loggerFile=/tmp/x", "jaasLogin=true",
            "authenticationPluginClassName=a.B", "anythingFile=x", "somePath=x")) {
            List<String> p = problems(pg, "jdbc:postgresql://db/x?ssl=true&" + bad);
            assertEquals(1, p.size(), bad);
            assertTrue(p.get(0).startsWith("url: the property"), p.get(0));
        }
        assertTrue(problems(pg, "jdbc:postgresql://db/x?ssl=true&sslmode=verify-full").isEmpty());

        SqlServerConnector sql = new SqlServerConnector();
        for (String bad : List.of("integratedSecurity=true", "authentication=ActiveDirectoryMSI",
            "authentication=activedirectoryintegrated", "authenticationScheme=NTLM", "trustStore=/x")) {
            assertEquals(1, problems(sql, "jdbc:sqlserver://db:1433;databaseName=x;" + bad).size(), bad);
        }
        assertTrue(problems(sql, "jdbc:sqlserver://db:1433;databaseName=x;authentication=SqlPassword;encrypt=true")
            .isEmpty());
    }

    private static SourceConfig src(JdbcConnector c, String url) {
        return new SourceConfig("s", c.kind(), Map.of("url", url, "username", "u"), "pw");
    }

    @Test
    void addressesTimeoutsAndServicesOutsideTheUrlAreRefused() {
        PostgresConnector pg = new PostgresConnector();
        for (String url : List.of("jdbc:postgresql://allowed.example.com/db?PGHOST=evil.example.com",
            "jdbc:postgresql://allowed.example.com/db?pghost=evil.example.com",
            "jdbc:postgresql://allowed.example.com/db?PGPORT=1&PGDBNAME=x",
            "jdbc:postgresql://allowed.example.com/db?loginTimeout=100000&socketTimeout=0",
            "jdbc:postgresql://allowed.example.com/db?socketTimeout=0&loginTimeout=0&connectTimeout=0",
            "jdbc:postgresql://allowed.example.com/db?cancelSignalTimeout=0",
            "jdbc:postgresql://allowed.example.com/db?sslResponseTimeout=0",
            "jdbc:postgresql://allowed.example.com/db?sslmode=require&SSLRESPONSETIMEOUT=0",
            "jdbc:postgresql://allowed.example.com/db?service=prod",
            "jdbc:postgresql://allowed.example.com/db?ssl%6Bey=/x")) {
            List<String> p = pg.validate(src(pg, url));
            assertEquals(1, p.size(), url);
            assertTrue(p.get(0).startsWith("url: the property"), p.get(0));
        }
        SqlServerConnector sql = new SqlServerConnector();
        for (String url : List.of("jdbc:sqlserver://allowed.example.com;server=evil.example.com",
            "jdbc:sqlserver://allowed.example.com;address=evil.example.com",
            "jdbc:sqlserver://allowed.example.com;addr=evil.example.com",
            "jdbc:sqlserver://allowed.example.com;network address=evil.example.com",
            "jdbc:sqlserver://allowed.example.com;network  Address=evil.example.com",
            "jdbc:sqlserver://allowed.example.com;authentication={ActiveDirectoryDefault}",
            "jdbc:sqlserver://allowed.example.com;authentication=\"ActiveDirectoryPassword\"",
            "jdbc:sqlserver://allowed.example.com;INTEGRATEDSECURITY=true",
            "jdbc:sqlserver://allowed.example.com;integratedSecurity={true}",
            "jdbc:sqlserver://allowed.example.com;loginTimeout=0;socketTimeout=0")) {
            assertEquals(1, sql.validate(src(sql, url)).size(), url);
        }
        assertTrue(sql.validate(src(sql, "jdbc:sqlserver://db;authentication={SqlPassword}")).isEmpty());
    }

    @Test
    void hostsComeFromTheDriversOwnParser() {
        PostgresConnector pg = new PostgresConnector();
        assertTrue(pg.hosts(src(pg, "jdbc:postgresql://allowed.example.com/db?PGHOST=evil.example.com"))
            .contains("evil.example.com"), "even a refused URL names every host it would reach");
        assertEquals(List.of("allowed.example.com", "evil.example.com"),
            pg.hosts(src(pg, "jdbc:postgresql://allowed.example.com,evil.example.com/db")));
        assertTrue(pg.hosts(src(pg, "jdbc:postgresql://[::1]:5432/db")).contains("[::1]"));
        SqlServerConnector sql = new SqlServerConnector();
        assertTrue(sql.hosts(src(sql, "jdbc:sqlserver://allowed.example.com;server=evil.example.com"))
            .contains("evil.example.com"));
        assertTrue(sql.hosts(src(sql, "jdbc:sqlserver://allowed.example.com;serverName={evil.example.com}"))
            .contains("evil.example.com"));
    }

    private static long driverThreads() {
        return Thread.getAllStackTraces().keySet().stream().filter(t -> t.getName().contains("PostgreSQL")).count();
    }

    @Test
    void aSilentServerLeavesNoDriverThreadsBehind() throws Exception {
        List<java.net.Socket> held = new java.util.concurrent.CopyOnWriteArrayList<>();
        try (java.net.ServerSocket silent = new java.net.ServerSocket(0, 50, java.net.InetAddress.getLoopbackAddress())) {
            Thread accept = Thread.ofPlatform().daemon().start(() -> {
                try {
                    while (true) {
                        held.add(silent.accept()); // accept, then never answer
                    }
                } catch (java.io.IOException e) {
                    // closed
                }
            });
            PostgresConnector pg = new PostgresConnector();
            String url = "jdbc:postgresql://127.0.0.1:" + silent.getLocalPort() + "/x";
            io.kilnmark.kiln.connect.ConnectorException refused = org.junit.jupiter.api.Assertions.assertThrows(
                io.kilnmark.kiln.connect.ConnectorException.class, () -> pg.test(src(pg,
                    url + "?socketTimeout=0&loginTimeout=0&connectTimeout=0"), java.time.Duration.ofSeconds(3)));
            assertTrue(refused.getMessage().contains("time limits"), refused.getMessage());
            for (int i = 0; i < 3; i++) {
                long start = System.nanoTime();
                org.junit.jupiter.api.Assertions.assertThrows(io.kilnmark.kiln.connect.ConnectorException.class,
                    () -> pg.test(src(pg, url), java.time.Duration.ofSeconds(3)));
                assertTrue(System.nanoTime() - start < 6_000_000_000L, "a check ends near its limit");
            }
            long deadline = System.nanoTime() + 10_000_000_000L;
            while (driverThreads() > 0 && System.nanoTime() < deadline) {
                Thread.sleep(200);
            }
            assertEquals(0, driverThreads(), "no driver connection threads remain");
            assertTrue(accept.isAlive());
        } finally {
            for (java.net.Socket s : held) {
                s.close();
            }
        }
    }

    @Test
    void namesEveryHostASourceWouldReach() {
        SqlServerConnector sql = new SqlServerConnector();
        assertEquals(List.of("db", "other", "spare"), sql.hosts(new SourceConfig("s", "sqlserver", Map.of("url",
            "jdbc:sqlserver://db:1433;serverName=other\\\\INST;failoverPartner=spare"), null)).stream().sorted().toList());
        S3Connector s3 = new S3Connector();
        assertEquals(List.of("store.example.com"), s3.hosts(new SourceConfig("b", "s3",
            Map.of("endpoint", "https://store.example.com:9000", "bucket", "b"), null)));
        assertEquals(List.of("s3.eu-west-2.amazonaws.com"), s3.hosts(new SourceConfig("b", "s3",
            Map.of("region", "eu-west-2", "bucket", "b"), null)));
        assertEquals(java.util.Set.of("url"), sql.addressKeys());
        assertEquals(".", sql.containerKind().separator());
    }
}

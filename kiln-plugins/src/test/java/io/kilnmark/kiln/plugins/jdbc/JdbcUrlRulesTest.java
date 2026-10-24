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

package io.kilnmark.server.quality;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

import io.kilnmark.kiln.plugins.jdbc.SqlDialect;

/**
 * The review's probes as tests: custom SQL that slipped past the old word filter, SQL that
 * escapes the query it is placed in, webhook paths that climb out of the allow-list, and regular
 * expressions outside the subset both engines read alike.
 */
class SafetyTest {

    private static final String URN = "urn:kilnmark:shop:sales.orders";

    private static void refused(String sql) {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
            () -> SqlTokens.checkCustomSql(sql, 10_000), sql);
        assertTrue(e.getMessage().startsWith("params.sql:"), e.getMessage());
    }

    @Test
    void customSqlBypassesOfTheOldWordFilterAreRefused() {
        // Quoted function names.
        refused("SELECT * FROM ${table} WHERE \"lo_export\"(4242, '/tmp/x') = 1");
        refused("SELECT * FROM ${table} WHERE \"pg_read_file\"('/etc/passwd') IS NOT NULL");
        // $q$ inside an identifier is not a dollar quote, so what follows is code.
        refused("SELECT 1 AS x$q$, pg_read_file('/etc/passwd') AS leak, lo_export(1,'/tmp/y') AS y$q$ FROM ${table}");
        // E'' strings end where a backslash does not escape the quote.
        refused("SELECT E'\\' ', lo_export(1,'/tmp/z'), ' ' FROM ${table}");
        // Brackets are arrays in PostgreSQL, not quoted names.
        refused("SELECT ARRAY[pg_sleep(100)] FROM ${table}");
        refused("SELECT pg_sleep_for('1 hour') FROM ${table}");
        refused("SELECT dblink_exec('dbname=postgres', 'DROP TABLE qa.t')");
        refused("SELECT pg_terminate_backend(pid) FROM pg_stat_activity");
        refused("SELECT pg_read_file('/etc/passwd') FROM ${table}");
        refused("SELECT pg_catalog.pg_read_binary_file('/etc/passwd')");
        refused("SELECT pg_ls_dir('/') FROM ${table}");
        refused("SELECT pg_reload_conf()");
        refused("SELECT set_config('transaction_read_only', 'off', true)");
        refused("SELECT pg_advisory_lock(42) FROM ${table}");
        refused("SELECT pg_notify('c', 'x')");
        refused("SELECT * FROM ${table} WHERE query_to_xml('COPY (SELECT 1) TO PROGRAM ''id''', true, false, '') IS NOT NULL");
        refused("SELECT table_to_xml('qa.t', true, false, '')");
        refused("SELECT nextval('qa.s') FROM ${table}");
        // Writes and second statements.
        refused("WITH x AS (DELETE FROM qa.t RETURNING 1) SELECT * FROM x");
        refused("SELECT * FROM t; DROP TABLE t");
        refused("SELECT * INTO copy_of_t FROM t");
        refused("SELECT * FROM t /* unclosed");
        refused("SELECT * FROM t /* nested /* */ still a comment; DROP TABLE t");
        refused("SELECT $1 FROM t");
        refused("DELETE FROM t");
        // Closing the query it is placed in (the SQL Server probe): parentheses must balance.
        refused("SELECT 1 AS a) f) DENY SELECT ON qa.t TO public COMMIT SHUTDOWN WITH NOWAIT SELECT (SELECT 1 AS b "
            + "FROM (SELECT 1 AS c");
        refused("SELECT 1 AS a) f) AS z DISABLE TRIGGER dbo.trg ON dbo.t COMMIT SELECT (SELECT 1 AS b FROM (SELECT 1 AS c");
    }

    @Test
    void functionsThatReachOtherDatabasesAreRefused() {
        refused("SELECT * FROM dblink('dbname=other', 'SELECT secret FROM t') AS x(secret text)");
        refused("SELECT * FROM public.\"dblink\"('host=elsewhere', 'SELECT 1') AS x(n int)");
        refused("SELECT \"DBLINK_CONNECT\"('c', 'dbname=other') FROM ${table}");
        refused("SELECT dblink_connect_u('c', 'dbname=other') FROM ${table}");
        refused("SELECT * FROM postgres_fdw_get_connections()");
        refused("SELECT postgres_fdw_disconnect_all() FROM ${table}");
        refused("SELECT \"postgres_fdw_handler\"() FROM ${table}");
        refused("SELECT * FROM ${table} WHERE pg_catalog.dblink_get_connections() IS NOT NULL");
    }

    @Test
    void ordinaryReadingSqlIsAccepted() {
        assertEquals("SELECT * FROM ${table} WHERE amount < 0",
            SqlTokens.checkCustomSql(" SELECT * FROM ${table} WHERE amount < 0; ", 10_000));
        SqlTokens.checkCustomSql("WITH x AS (SELECT 1 AS a) SELECT * FROM x", 10_000);
        SqlTokens.checkCustomSql("SELECT * FROM t WHERE note = 'delete me; now' AND \"update\" > 0 /* drop; */", 10_000);
        SqlTokens.checkCustomSql("SELECT CASE WHEN a > 0 THEN 'x' ELSE $tag$it's; fine$tag$ END FROM t", 10_000);
        SqlTokens.checkCustomSql("SELECT E'it\\'s' FROM t -- comment; DROP TABLE t", 10_000);
        SqlTokens.checkCustomSql("SELECT * FROM t WHERE \"set\" = 1 AND amount > 1e3", 10_000);
    }

    @Test
    void generatedQueriesAreOneStatement() {
        assertTrue(SqlTokens.isSingleStatement("SELECT (SELECT COUNT(*) FROM \"s\".\"t\" t), (SELECT COUNT(*) FROM "
            + "(SELECT * FROM \"s\".\"t\") f)"));
        assertFalse(SqlTokens.isSingleStatement("SELECT 1 AS a) f) AS z DENY SELECT ON dbo.t TO public COMMIT SELECT "
            + "(SELECT 1 AS b FROM (SELECT 1 AS c"), "SQL that closes the query around it");
        assertFalse(SqlTokens.isSingleStatement("SELECT 1; SELECT 2"));
    }

    private static RuleDraft.Context context(String kind, boolean sql, boolean customSql) {
        return new RuleDraft.Context() {
            @Override
            public Map<String, String> fields() {
                return Map.of("amount", "decimal", "name", "text");
            }

            @Override
            public String sourceKind() {
                return kind;
            }

            @Override
            public boolean sql() {
                return sql;
            }

            @Override
            public Optional<Map<String, String>> fieldsOf(String urn) {
                return Optional.empty();
            }

            @Override
            public List<String> webhookAllowList() {
                return List.of();
            }

            @Override
            public boolean customSqlEnabled() {
                return customSql;
            }
        };
    }

    private static RuleDraft custom(String sql) throws Exception {
        return new RuleDraft(URN, null, null, "custom_sql", null, Facts.JSON.readTree("{\"sql\":\"" + sql + "\"}"), null,
            null, null, null, null);
    }

    @Test
    void customSqlIsOffByDefaultAndRefusedOnSqlServer() throws Exception {
        IllegalArgumentException off = assertThrows(IllegalArgumentException.class,
            () -> custom("SELECT * FROM ${table}").check(context("postgresql", true, false), "admin"));
        assertTrue(off.getMessage().contains("turned off"), off.getMessage());
        IllegalArgumentException mssql = assertThrows(IllegalArgumentException.class,
            () -> custom("SELECT * FROM ${table}").check(context("sqlserver", true, true), "admin"));
        assertTrue(mssql.getMessage().contains("only on PostgreSQL"), mssql.getMessage());
        assertNotNull(custom("SELECT * FROM ${table}").check(context("postgresql", true, true), "admin"));

        // And at run time, whatever is stored: off on this server, or a SQL Server source.
        Rule rule = custom("SELECT * FROM ${table}").check(context("postgresql", true, true), "admin");
        SqlRules.Plan pg = SqlRules.plan(rule, SqlDialect.POSTGRESQL, new SqlRules.Table("s", "t"), Map.of(), u -> null);
        SqlRules.Plan ms = SqlRules.plan(rule, SqlDialect.SQL_SERVER, new SqlRules.Table("s", "t"), Map.of(), u -> null);
        assertNotNull(new RuleRunner(Duration.ofSeconds(5), 0, 1 << 20, false).customSqlRefusal(rule, SqlDialect.POSTGRESQL,
            pg));
        assertTrue(new RuleRunner(Duration.ofSeconds(5), 0, 1 << 20, true).customSqlRefusal(rule, SqlDialect.SQL_SERVER, ms)
            .contains("only on PostgreSQL"));
        assertNull(new RuleRunner(Duration.ofSeconds(5), 0, 1 << 20, true).customSqlRefusal(rule, SqlDialect.POSTGRESQL, pg));
    }

    @Test
    void placeholdersAreFilledInOnePass() {
        assertEquals("SELECT \"${column}\" FROM \"s\".\"t\"", SqlRules.placeholders("SELECT ${column} FROM ${table}",
            "\"s\".\"t\"", "\"${column}\""), "a name holding ${column} is not filled in again");
        assertEquals("SELECT {property} FROM {object}", StandardFormats.odcsPlaceholders("SELECT ${column} FROM ${table}"));
    }

    @Test
    void webhookPathsCannotClimbOutOfTheAllowList() {
        List<String> allow = List.of("https://hooks.example.com/kilnmark/", "http://127.0.0.1:47391/hooks/");
        assertFalse(RuleDraft.allowed("https://hooks.example.com/kilnmark/../admin/delete", allow));
        assertFalse(RuleDraft.allowed("https://hooks.example.com/kilnmark/%2e%2e/admin", allow));
        assertFalse(RuleDraft.allowed("https://hooks.example.com/kilnmark/%2E%2E/admin", allow));
        assertFalse(RuleDraft.allowed("http://127.0.0.1:47391/hooks/..%2f..%2fq/health", allow));
        assertFalse(RuleDraft.allowed("https://hooks.example.com/kilnmark/./x", allow));
        assertFalse(RuleDraft.allowed("https://hooks.example.com/kilnmark/a%5c..%5cb", allow));
        assertTrue(RuleDraft.allowed("https://hooks.example.com/kilnmark/?x=1", allow));
        assertTrue(RuleDraft.allowed("https://hooks.example.com/kilnmark/quality.v1", allow), "dots in a name are fine");
    }

    @Test
    void webhookPathsCannotClimbOutThroughMatrixParameters() {
        List<String> allow = List.of("https://hooks.example.com/kilnmark/", "http://127.0.0.1:47391/hooks/");
        // Servers that strip ";..." from a segment (Tomcat, Jetty) then read it as a dot segment.
        for (String url : List.of("https://hooks.example.com/kilnmark/..;x/admin",
            "https://hooks.example.com/kilnmark/..;/admin", "https://hooks.example.com/kilnmark/.;x/a",
            "https://hooks.example.com/kilnmark/a/..;jsessionid=1/../admin", "http://127.0.0.1:47391/hooks/..;/q/health",
            // An encoded semicolon, decoded before the segment is read.
            "https://hooks.example.com/kilnmark/..%3bx/admin", "https://hooks.example.com/kilnmark/..%3Bx/admin",
            // Any semicolon at all: no webhook needs one (see RuleDraft.plainPath).
            "https://hooks.example.com/kilnmark/quality;v=1")) {
            assertFalse(RuleDraft.allowed(url, allow), url);
            assertEquals("not on the allow-list", new Webhooks(allow, Duration.ofSeconds(1)).send(url, "{}"),
                "and checked again, with the same function, at send time: " + url);
        }
        assertTrue(RuleDraft.allowed("https://hooks.example.com/kilnmark/hook?a=1;b=2", allow),
            "semicolons in the query are not part of the path");
    }

    @Test
    void webhookPathsTakeOnlyHarmlessEscapesAndAsciiAndTheDefaultPortIsNoPort() {
        List<String> allow = List.of("https://hooks.example.com/kilnmark/", "http://127.0.0.1:47391/hooks/");
        for (String url : List.of("https://hooks.example.com/kilnmark/%252e%252e/admin", // double-decoded dots
            "https://hooks.example.com/kilnmark/a%00b", "https://hooks.example.com/kilnmark/%c0%ae%c0%ae/admin",
            "https://hooks.example.com/kilnmark/%20x", "https://hooks.example.com/kilnmark/%zz",
            "https://hooks.example.com/kilnmark/%4", "https://hooks.example.com/kilnmark/caf\u00e9",
            "https://hooks.example.com/kilnmark/x?q=\u00e9")) {
            assertFalse(RuleDraft.allowed(url, allow), url);
        }
        assertTrue(RuleDraft.allowed("https://hooks.example.com/kilnmark/%41b-c_d~", allow), "unreserved escapes");
        assertTrue(RuleDraft.allowed("https://hooks.example.com:443/kilnmark/x", allow), "443 is https's own port");
        assertTrue(RuleDraft.allowed("https://hooks.example.com/kilnmark/x", List.of("https://hooks.example.com:443/")));
        assertFalse(RuleDraft.allowed("https://hooks.example.com:8443/kilnmark/x", allow));
        assertTrue(RuleDraft.allowed("http://example.net:80/a", List.of("http://example.net/")));
        assertFalse(RuleDraft.allowed("http://example.net:443/a", List.of("http://example.net/")));
    }

    @Test
    void regexesStayInTheSubsetBothEnginesRead() {
        for (String ok : List.of("^[A-Z]{2}[0-9]{4}$", "^\\p{Lu}\\p{Ll}+$", "^(?:ab|cd)*?x$", "^[^@\\s]+@[^@]+\\.[a-z]{2,}$",
            "^\\d{3}-\\d{4}$", "^a\\.b\\+c$")) {
            assertNull(Regexes.problem(ok), ok);
        }
        for (String bad : List.of("(a)\\1", "(?=x)y", "(?i)abc", "a*+", "\\bword\\b", "\\Qx\\E", "a{1,300}", "[[:alpha:]]",
            "\\p{IsGreek}", "(?<name>x)", "[a-z&&[^e]]", "a{", "\\u0041")) {
            assertNotNull(Regexes.problem(bad), bad);
        }
        assertEquals("^[[:upper:]][[:lower:]]+ [0-9]{2}[^[:upper:]]$", Regexes.toPostgres("^\\p{Lu}\\p{Ll}+ [0-9]{2}[^\\p{Lu}]$"));
    }

    @Test
    void streamedMatchingIsBoundedInTimeAndLength() {
        Pattern catastrophic = Pattern.compile("^(.*a){12}x$");
        assertNull(Regexes.problem(catastrophic.pattern()), "inside the subset, yet exponential");
        long start = System.nanoTime();
        assertNull(Regexes.find(catastrophic, "a".repeat(40)), "gives up instead of running for ever");
        assertTrue(System.nanoTime() - start < 2_000_000_000L, "and quickly");
        assertNull(Regexes.find(Pattern.compile("x"), "x".repeat(Regexes.MAX_VALUE + 1)), "too long to match");
        assertEquals(Boolean.TRUE, Regexes.find(Pattern.compile("^a+$"), "aaa"));
        assertEquals(Boolean.FALSE, Regexes.find(Pattern.compile("^a+$"), "aab"));
    }

    @Test
    void viewersGetEvidenceWithoutValues() {
        var e = Facts.JSON.createObjectNode().put("values", 10).put("min", "1").put("max", "9").put("fenceLow", "0")
            .put("detail", "Quartile fences 0 to 9").put("latest", "2026-10-01T00:00:00Z");
        e.putArray("domain").add("open");
        var viewer = Masking.evidence(e, false);
        assertEquals(1, viewer.size());
        assertEquals(10, viewer.get("values").asInt());
        assertEquals(e, Masking.evidence(e, true));
    }
}

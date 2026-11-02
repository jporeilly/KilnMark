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

    private static void refusedAction(String sql, String why) {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
            () -> SqlTokens.checkAction(sql, 10_000), sql);
        assertTrue(e.getMessage().contains(why), sql + " gave " + e.getMessage());
    }

    /** A flow's sql node (ADR 0028): statements that change data or tables, never a query or a way out. */
    @Test
    void aSqlNodeTakesStatementsThatChangeDataOrTables() {
        for (String ok : List.of("TRUNCATE staging.orders", "INSERT INTO t (id, n) VALUES (1, nextval('s'))",
            "INSERT INTO t SELECT * FROM s ON CONFLICT (id) DO NOTHING", "UPDATE t SET n = n + 1 WHERE id = 3",
            "DELETE FROM t WHERE at < now() - interval '30 days'", "CREATE INDEX t_n ON t (n)", "DROP INDEX t_n",
            "CREATE TABLE IF NOT EXISTS staging.x (id integer, \"user\" text)", "ALTER TABLE t ADD COLUMN m integer",
            "CALL refresh_totals()", "ANALYZE t", "REFRESH MATERIALIZED VIEW totals", "LOCK TABLE t IN EXCLUSIVE MODE",
            "MERGE INTO t USING s ON t.id = s.id WHEN MATCHED THEN UPDATE SET n = s.n", "TRUNCATE t;")) {
            assertEquals(ok.endsWith(";") ? ok.substring(0, ok.length() - 1) : ok, SqlTokens.checkAction(ok, 10_000));
        }
        // Never a query, transaction control, settings or roles, files, or rights
        refusedAction("SELECT * FROM t", "a statement starts with");
        refusedAction("WITH x AS (DELETE FROM t RETURNING *) SELECT * FROM x", "a statement starts with");
        for (String start : List.of("BEGIN", "COMMIT", "ROLLBACK", "SAVEPOINT a", "SET ROLE postgres",
            "SET search_path = x", "RESET ALL", "COPY t FROM '/etc/passwd'", "DO $$ BEGIN END $$", "GRANT ALL ON t TO x",
            "REVOKE ALL ON t FROM x", "VACUUM t", "EXECUTE p", "LISTEN x", "LOAD 'x'", "SHOW ALL", "EXPLAIN DELETE FROM t")) {
            refusedAction(start, "a statement starts with");
        }
        refusedAction("DELETE FROM t RETURNING id", "'returning' is not allowed");
        refusedAction("CREATE ROLE x LOGIN", "'role' is not allowed");
        refusedAction("ALTER USER x WITH SUPERUSER", "'user' is not allowed");
        refusedAction("CREATE EXTENSION file_fdw", "'extension' is not allowed");
        refusedAction("CREATE OR REPLACE FUNCTION f() RETURNS int AS 'select 1' LANGUAGE sql", "'function' is not allowed");
        refusedAction("CREATE SERVER s FOREIGN DATA WRAPPER postgres_fdw", "'server' is not allowed");
        refusedAction("ALTER TABLE t OWNER TO x", "'owner' is not allowed");
        refusedAction("ALTER DEFAULT PRIVILEGES GRANT ALL ON TABLES TO x", "'privileges' is not allowed");
        refusedAction("CREATE SCHEMA s AUTHORIZATION x", "'authorization' is not allowed");
        refusedAction("ALTER SYSTEM SET work_mem = '1GB'", "'system' is not allowed");
        refusedAction("CREATE TRIGGER t BEFORE INSERT ON t EXECUTE FUNCTION f()", "'trigger' is not allowed");
        refusedAction("INSERT INTO t SELECT pg_read_file('/etc/passwd')", "'pg_read_file' is not allowed");
        refusedAction("INSERT INTO t SELECT * FROM dblink('x', 'y') AS r(a int)", "'dblink' is not allowed");
        refusedAction("UPDATE t SET n = 1 WHERE id = $1", "parameters such as $1");
        refusedAction("TRUNCATE t; DROP TABLE s", "one statement only");
        refusedAction("TRUNCATE t)", "closes more than was opened");
        refusedAction("  ", "one SQL statement");
        refusedAction("INSERT INTO t VALUES ('not closed)", "not closed");
    }

    /**
     * What PostgreSQL reads as SQL never hides in what the check reads as a comment: a carriage return
     * ends a line comment for both (found by the review of the sql node), and a U&"..." name, whose
     * escapes could spell a refused function, is refused. Both checks share the tokenizer.
     */
    @Test
    void nothingHidesInACommentOrAnEscapedName() {
        refusedAction("CREATE --x\rFUNCTION f() RETURNS int SECURITY DEFINER LANGUAGE sql AS 'select 1'",
            "'function' is not allowed");
        refusedAction("ALTER --x\rTABLE t DISABLE ROW LEVEL SECURITY", "'security' is not allowed");
        refusedAction("INSERT INTO t VALUES (1) --c\r; GRANT ALL ON t TO PUBLIC", "one statement only");
        refusedAction("INSERT INTO t VALUES (1) --c\r\n; COMMIT", "one statement only");
        refusedAction("INSERT INTO t SELECT 1 FROM (SELECT U&\"set_config\"('role', 'x', true)) s",
            "names written as U&");
        refusedAction("INSERT INTO t SELECT u&\"pg_terminate_backend\"(1)", "names written as U&");
        refusedAction("INSERT INTO t VALUES (1)\u0000; DROP TABLE t", "a control character");
        refusedAction("TRUNCATE\u001ct", "a control character");
        // A comment still ends the line it is on, whichever line ending was pasted
        assertEquals("TRUNCATE t -- empty it", SqlTokens.checkAction("TRUNCATE t -- empty it\r\n", 10_000));
        assertEquals("UPDATE t SET n = 1 -- all\n WHERE id = 2",
            SqlTokens.checkAction("UPDATE t SET n = 1 -- all\n WHERE id = 2", 10_000));
        // Custom rules share the tokenizer
        refused("SELECT * FROM t --x\r; DELETE FROM t");
        refused("SELECT U&\"pg_read_file\"('x')");
        SqlTokens.checkCustomSql("SELECT *\r\nFROM t -- note\r\nWHERE a > 0", 10_000);
    }

    /**
     * The second review of the sql node: a dollar-quote tag is read as PostgreSQL and the driver read
     * it (any non-ASCII character), a query held in a string is refused, E'' strings are refused, and
     * whatever the driver would split into statements is refused whatever this check made of it.
     */
    @Test
    void dollarTagsStringsAndTheDriverAgreeOnOneStatement() {
        String tag = "UPDATE victim SET a = a WHERE a::text <> $\u20ac$ ' $\u20ac$ ; COMMIT; DELETE FROM victim; --'";
        refusedAction(tag, "one statement only");
        refused("SELECT $\u20ac$ ' $\u20ac$ AS x; COMMIT; CREATE TABLE leak AS SELECT 1 AS y; --'");
        // A tag of non-ASCII letters still quotes: nothing inside it is read as SQL
        assertEquals("UPDATE t SET note = $\u00e9t\u00e9$ ; DROP TABLE t $\u00e9t\u00e9$",
            SqlTokens.checkAction("UPDATE t SET note = $\u00e9t\u00e9$ ; DROP TABLE t $\u00e9t\u00e9$", 10_000));
        refusedAction("UPDATE victim SET a = a WHERE EXISTS (SELECT 1 FROM ts_stat('SELECT to_tsvector("
            + "set_config(''role'', ''admin_grp'', false))'))", "'ts_stat' is not allowed");
        refused("SELECT * FROM ts_rewrite('a'::tsquery, 'SELECT t, s FROM aliases')");
        refusedAction("UPDATE t SET a = E'x'\n'\\' || '; COMMIT; DELETE FROM t; --'", "E'...' strings are not allowed");
        refused("SELECT E'x'\n'\\' || '; DELETE FROM t; --' AS a");
        // A word written straight after a number is still a word
        refusedAction("DELETE FROM t WHERE a = 1returning a", "'returning' is not allowed");
        // The third review: pg_settings changes a session's settings through its rules, views and
        // routines are code that runs later
        refusedAction("UPDATE pg_settings SET setting = 'off' WHERE name = 'standard_conforming_strings'",
            "'pg_settings' is not allowed");
        refused("SELECT * FROM pg_catalog.pg_settings");
        refusedAction("CREATE OR REPLACE VIEW v AS SELECT * FROM t", "'view' is not allowed");
        refusedAction("CREATE MATERIALIZED VIEW m AS SELECT * FROM t", "'view' is not allowed");
        refusedAction("ALTER VIEW v SET (security_invoker = false)", "'view' is not allowed");
        refusedAction("ALTER ROUTINE f() SET search_path = evil", "'routine' is not allowed");
        assertEquals(null, SqlTokens.driverSplits("INSERT INTO t VALUES ('a;b')"));
        assertEquals("one statement only", SqlTokens.driverSplits("INSERT INTO t VALUES (1); DELETE FROM t"));
    }

    @Test
    void ordinaryReadingSqlIsAccepted() {
        assertEquals("SELECT * FROM ${table} WHERE amount < 0",
            SqlTokens.checkCustomSql(" SELECT * FROM ${table} WHERE amount < 0; ", 10_000));
        SqlTokens.checkCustomSql("WITH x AS (SELECT 1 AS a) SELECT * FROM x", 10_000);
        SqlTokens.checkCustomSql("SELECT * FROM t WHERE note = 'delete me; now' AND \"update\" > 0 /* drop; */", 10_000);
        SqlTokens.checkCustomSql("SELECT CASE WHEN a > 0 THEN 'x' ELSE $tag$it's; fine$tag$ END FROM t", 10_000);
        SqlTokens.checkCustomSql("SELECT 'it''s' FROM t -- comment; DROP TABLE t", 10_000);
        // E'' strings are refused: their escapes can hide where a string ends (review of the sql node)
        refused("SELECT E'it\\'s' FROM t");
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
        assertTrue(off.getMessage().contains("allow_sql"), off.getMessage());
        // A rule saved switched off never runs, so a source without Allow SQL still takes it (to rename or turn off).
        assertNotNull(new RuleDraft(URN, null, null, "custom_sql", null,
            Facts.JSON.readTree("{\"sql\":\"SELECT * FROM ${table}\"}"), null, null, null, null, false)
            .check(context("postgresql", true, false), "admin"), "a rule switched off saves without allow_sql");
        IllegalArgumentException mssql = assertThrows(IllegalArgumentException.class,
            () -> custom("SELECT * FROM ${table}").check(context("sqlserver", true, true), "admin"));
        assertTrue(mssql.getMessage().contains("only on PostgreSQL"), mssql.getMessage());
        assertNotNull(custom("SELECT * FROM ${table}").check(context("postgresql", true, true), "admin"));

        // And at run time, whatever is stored: a source without allow_sql, or a SQL Server source.
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

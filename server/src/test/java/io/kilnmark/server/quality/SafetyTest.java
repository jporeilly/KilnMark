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
        refusedAction("CREATE SCHEMA s AUTHORIZATION x", "'schema' is not allowed");
        refusedAction("ALTER TABLE t OWNER TO x AUTHORIZATION y", "'owner' is not allowed");
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
        // A word written straight after a number is refused (it was read as a word until the second review)
        refusedAction("DELETE FROM t WHERE a = 1returning a", "a number is followed straight away by a letter");
        // The third review: pg_settings changes a session's settings through its rules, views and
        // routines are code that runs later
        refusedAction("UPDATE pg_settings SET setting = 'off' WHERE name = 'standard_conforming_strings'",
            "'pg_settings' is not allowed");
        refused("SELECT * FROM pg_catalog.pg_settings");
        refusedAction("CREATE OR REPLACE VIEW v AS SELECT * FROM t", "'view' is not allowed");
        refusedAction("CREATE MATERIALIZED VIEW m AS SELECT * FROM t", "'view' is not allowed");
        refusedAction("ALTER VIEW v SET (security_invoker = false)", "'view' is not allowed");
        refusedAction("ALTER ROUTINE f() SET search_path = evil", "'routine' is not allowed");
        // The fourth review: a lone surrogate reaches the database as ?, and GRANT can sit inside CREATE SCHEMA
        refusedAction("UPDATE t SET a = a WHERE '{}'::jsonb \uD800set_config('role', 'other', true) IS NOT NULL",
            "not valid text");
        refused("SELECT 1 WHERE '{}'::jsonb \uDC00set_config('search_path', 'evil', true) IS NOT NULL");
        refusedAction("INSERT INTO t VALUES ('\uD83D')", "not valid text");
        assertEquals("INSERT INTO t VALUES ('\uD83D\uDE00')",
            SqlTokens.checkAction("INSERT INTO t VALUES ('\uD83D\uDE00')", 10_000), "a pair is a character");
        refusedAction("CREATE SCHEMA s2 CREATE TABLE s2.x (a int) GRANT ALL ON TABLE public.t TO PUBLIC",
            "is not allowed");
        // GRANT and REVOKE are refused wherever they stand, not only through the schema they sat in
        refusedAction("CREATE TABLE s2.x (a int) GRANT ALL ON TABLE public.t TO PUBLIC", "'grant' is not allowed");
        refusedAction("CREATE TABLE s2.x (a int) REVOKE ALL ON TABLE public.t FROM x", "'revoke' is not allowed");
        // The fifth review: a schema named as the login changes what unqualified names mean
        refusedAction("CREATE SCHEMA kiln", "'schema' is not allowed");
        refusedAction("ALTER SCHEMA s2 RENAME TO kiln", "'schema' is not allowed");
        assertEquals(null, SqlTokens.driverSplits("INSERT INTO t VALUES ('a;b')"));
        assertEquals("one statement only", SqlTokens.driverSplits("INSERT INTO t VALUES (1); DELETE FROM t"));
    }

    /**
     * GROUP is two words in PostgreSQL: the clause GROUP BY, which any statement that totals rows
     * needs, and a kind of account (CREATE GROUP and the like). A sql node takes the first and refuses
     * the second, however the words are spaced, cased or commented. It refused both until 0.1.2, so
     * the sample flow sample-sql-before-and-after could not run.
     */
    @Test
    void aSqlNodeTakesGroupByAndRefusesAGroupAccount() {
        for (String ok : List.of(
            "INSERT INTO sample_order_totals SELECT status, count(*), sum(shipping_fee) FROM shop_orders GROUP BY status",
            "insert into t select a, count(*) from s group by a",
            "INSERT INTO t SELECT a FROM s GrOuP\r\n\t bY a",
            "INSERT INTO t SELECT a FROM s GROUP /* by key */ BY a",
            "INSERT INTO t SELECT a FROM s GROUP -- by key\n BY a",
            "INSERT INTO t SELECT a FROM s WHERE b = 'x' GROUP BY a HAVING count(*) > 1",
            "INSERT INTO t SELECT a FROM s WHERE b > 1 GROUP BY a",
            "INSERT INTO t SELECT a FROM (SELECT a FROM s) x GROUP BY a",
            "INSERT INTO t SELECT a FROM \"create\" GROUP BY a",
            "CREATE TABLE totals AS SELECT a, sum(n) AS n FROM s GROUP BY a",
            "UPDATE t SET n = (SELECT count(*) FROM s WHERE s.a = t.a GROUP BY s.a)",
            "DELETE FROM t WHERE a IN (SELECT a FROM s GROUP BY a HAVING count(*) > 1)",
            "MERGE INTO t USING (SELECT a, sum(n) AS n FROM s GROUP BY a) x ON t.a = x.a WHEN MATCHED THEN UPDATE "
                + "SET n = x.n",
            "INSERT INTO t SELECT a, b FROM s GROUP BY GROUPING SETS ((a), (b))",
            // a column or table called group, quoted, is a name as before
            "INSERT INTO t (\"group\") SELECT \"group\" FROM s GROUP BY \"group\"")) {
            assertEquals(ok, SqlTokens.checkAction(ok, 10_000));
        }
        // The account: created, changed, dropped or renamed, also one called by, which PostgreSQL allows
        for (String account : List.of("CREATE GROUP staff", "CREATE GROUP by", "create group By with login",
            "CREATE GROUP \"by\"", "CREATE /* c */ GROUP /* c */ BY", "CREATE --c\rGROUP --c\nBY",
            "CREATE\tGROUP\u000bBY", "ALTER GROUP staff ADD USER x", "ALTER GROUP by ADD USER x",
            "ALTER GROUP by RENAME TO other", "DROP GROUP staff", "DROP GROUP by", "DROP GROUP IF EXISTS by",
            "DROP GROUP staff, by",
            // where a statement names who is given a right, or whose members a new account joins
            "ALTER TABLE t RENAME TO GROUP BY", "DELETE FROM t WHERE a IN GROUP BY a", "DELETE FROM GROUP BY a",
            "INSERT INTO t SELECT a, GROUP BY a", "COMMENT ON GROUP by IS 'staff'",
            // the word alone, or before anything but an unquoted BY
            "INSERT INTO t SELECT a FROM s GROUP", "INSERT INTO t SELECT a FROM s GROUP \"by\" a",
            "INSERT INTO t SELECT a FROM s GROUP 'by' a", "INSERT INTO t SELECT a AS group FROM s",
            "INSERT INTO t SELECT a FROM s GROUP bye",
            // every use is checked, not only the first
            "DROP TABLE t GROUP BY a CREATE GROUP by")) {
            refusedAction(account, "'group' is not allowed");
        }
        // Words that name the account's rights are refused as before, wherever GROUP BY stands
        refusedAction("CREATE TABLE x AS SELECT a FROM s GROUP BY a GRANT ALL ON x TO GROUP by", "'grant' is not allowed");
        refusedAction("CREATE ROLE x IN GROUP by", "'role' is not allowed");
        refusedAction("INSERT INTO t SELECT a FROM s GROUP BY a RETURNING a", "'returning' is not allowed");
        // The other five words of its kind have no second meaning a statement needs: refused wherever they stand
        refusedAction("INSERT INTO t SELECT user", "'user' is not allowed");
        refusedAction("INSERT INTO t SELECT a FROM s GROUP BY role", "'role' is not allowed");
        refusedAction("DROP EXTENSION IF EXISTS by", "'extension' is not allowed");
        refusedAction("DROP SERVER by", "'server' is not allowed");
        refusedAction("CREATE FOREIGN TABLE by (a int) SERVER s", "'foreign' is not allowed");
        refusedAction("ALTER TABLE t ADD FOREIGN KEY (a) REFERENCES s (a)", "'foreign' is not allowed");
        // The ordered-set form (WITHIN GROUP) stays refused: only GROUP BY is let through
        refusedAction("INSERT INTO t SELECT percentile_cont(0.5) WITHIN GROUP (ORDER BY a) FROM s",
            "'group' is not allowed");
        // Custom SQL rules and wait-for queries never refused GROUP BY, and still read only
        SqlTokens.checkCustomSql("SELECT a FROM t GROUP BY a HAVING count(*) > 1", 10_000);
        refused("SELECT a FROM t GROUP BY a; CREATE GROUP by");
    }

    /** Every form of JDBC escape the driver reads, and ways of writing each: spacing, case, comments, nesting. */
    private static final List<String> ESCAPES = List.of("{oj GROUP by}", "{OJ GROUP by}", "{ oj GROUP by }",
        "{oj\tGROUP by}", "{oj/* c */GROUP by}", "{oj -- c\n GROUP by}", "{fn xROLE r}", "{FN  xROLE r}", "{fn\nxROLE r}",
        "{fn/* c */xROLE r}", "{\tfn xROLE r}", "{fn user()}", "{fn concat('a', 'b')}", "{fn length({fn user()})}",
        "{d '2024-01-01'}", "{D '2024-01-01'}", "{t '10:00:00'}", "{ts '2024-01-01 10:00:00'}",
        "{call refresh_totals()}", "{CALL refresh_totals()}", "{?= call f()}", "{ ? = call f() }", "{escape '!'}",
        "{limit 1}", "{limit 1 offset 2}", "{}", "{", "}", "}{", "{x}", "{table}", "{{fn xROLE r}}");

    /**
     * The database driver rewrites JDBC escapes after a check has read the text, so what ran was not
     * what was checked: {@code {fn xWORD ...}} lost its braces, its {@code fn} and the first character
     * of the word (every refused word passed, in 0.1.2 and earlier), and {@code {oj GROUP by}} hid the
     * word before GROUP from the GROUP BY rule (never released). No brace is taken outside quotes.
     */
    @Test
    void jdbcEscapesAreRefusedSoWhatIsCheckedIsWhatIsSent() throws Exception {
        // What the driver made of them: why they are refused
        assertEquals("CREATE ROLE r", org.postgresql.core.Parser.replaceProcessing("CREATE {fn xROLE r}", true, true));
        assertEquals("CREATE GROUP by", org.postgresql.core.Parser.replaceProcessing("CREATE {oj GROUP by}", true, true));
        // The sql node: the bypasses found, then every escape form wherever it stands
        for (String bypass : List.of("CREATE {fn xROLE r}", "CREATE {fn xVIEW v AS SELECT 1 AS a}",
            "CREATE {fn xRULE r AS ON INSERT TO t DO ALSO DELETE FROM t}", "ALTER {fn xROLE me SET search_path = x}",
            "ALTER {fn xDEFAULT PRIVILEGES GRANT SELECT ON TABLES TO PUBLIC}", "ALTER TABLE t {fn xOWNER TO x}",
            "DELETE FROM t {fn xRETURNING a}", "INSERT INTO t VALUES (2) {fn xRETURNING a}",
            "{fn xGRANT ALL ON TABLE t TO PUBLIC}", "CREATE {oj GROUP by}", "DROP {oj GROUP by}, other",
            "ALTER {oj GROUP by} RENAME TO other", "CREATE {oj GROUP by} WITH LOGIN", "CREATE {d GROUP by}")) {
            refusedAction(bypass, "'{' and '}' are not allowed outside quotes");
        }
        for (String escape : ESCAPES) {
            for (String statement : List.of("CREATE " + escape, "INSERT INTO t SELECT a FROM s WHERE b = " + escape,
                "DELETE FROM t WHERE a IN (SELECT a FROM s GROUP BY a) " + escape, escape + " TRUNCATE t",
                "UPDATE t SET a = 1 WHERE b = 'x' " + escape + " AND c = $q$ y $q$")) {
                refusedAction(statement, "'{' and '}' are not allowed outside quotes");
            }
        }
        // Custom SQL rules, a read's query and a wait-for query share the other check
        for (String bypass : List.of("SELECT * {fn xINTO leak} FROM t", "SELECT a FROM t {fn xFOR UPDATE}",
            "WITH x AS ({fn xDELETE FROM t RETURNING 1}) SELECT * FROM x", "SELECT * FROM ${table} {fn xINTO leak}",
            "SELECT {fn user()} FROM ${table}", "SELECT {d '2024-01-01'}")) {
            refusedCustom(bypass, "'{' and '}' are not allowed outside quotes");
        }
        for (String escape : ESCAPES) {
            refusedCustom("SELECT * FROM ${table} WHERE a = 1 " + escape, "'{' and '}' are not allowed outside quotes");
            refusedCustom("SELECT " + escape + " FROM t", "'{' and '}' are not allowed outside quotes");
        }
        // A rule's two placeholders, exactly as written, are the only braces taken; nothing else may borrow the form
        SqlTokens.checkCustomSql("SELECT * FROM ${table} WHERE ${column} IS NULL", 10_000);
        for (String near : List.of("SELECT * FROM ${ table}", "SELECT * FROM ${table }", "SELECT * FROM $ {table}",
            "SELECT * FROM ${tables}", "SELECT * FROM ${fn}", "SELECT * FROM ${fn xinto}", "SELECT * FROM {table}",
            "SELECT * FROM ${table/* c */}", "SELECT * FROM ${\"table\"}", "SELECT * FROM ${table", "SELECT 1 ${")) {
            refusedCustom(near, "'{' and '}' are not allowed outside quotes");
        }
        refusedAction("TRUNCATE ${table}", "'{' and '}' are not allowed outside quotes");
        // Braces PostgreSQL itself reads are all inside quotes or comments, and are taken as before
        for (String ok : List.of("INSERT INTO t (tags) VALUES ('{1,2}'::int[])", "UPDATE t SET doc = '{\"a\": {\"b\": 1}}'::jsonb",
            "DELETE FROM t WHERE code ~ '^[A-Z]{3}[0-9]{2}$'", "UPDATE t SET note = '{fn xROLE r}'",
            "UPDATE t SET note = $q${fn xROLE r}$q$ WHERE id = 1", "INSERT INTO \"t{fn x}\" (\"{a}\") VALUES (1)",
            "TRUNCATE t /* {fn xROLE r} */", "TRUNCATE t -- {oj x}", "INSERT INTO t VALUES (ARRAY[1, 2])")) {
            assertEquals(ok, SqlTokens.checkAction(ok, 10_000));
            assertNull(SqlTokens.sentAsWritten(ok), ok);
        }
        for (String ok : List.of("SELECT * FROM ${table} WHERE tags = '{1,2}'::int[]",
            "SELECT * FROM ${table} WHERE ${column} !~ '^[0-9]{4}$' /* {fn x} */",
            "SELECT * FROM t WHERE doc @> '{\"a\": 1}' AND \"{odd}\" = $x${d '2024-01-01'}$x$")) {
            assertEquals(ok, SqlTokens.checkCustomSql(ok, 10_000));
        }
        // The second line asks the driver itself: it would change every escape it reads, and nothing else
        for (String rewritten : List.of("CREATE {fn xROLE r}", "CREATE {oj GROUP by}", "SELECT {d '2024-01-01'}",
            "SELECT {t '10:00:00'}", "SELECT {ts '2024-01-01 10:00:00'}", "SELECT {fn user()}", "SELECT a LIKE 'x' {escape '!'}")) {
            assertEquals(SqlTokens.NO_BRACES, SqlTokens.sentAsWritten(rewritten), rewritten);
        }
        assertNull(SqlTokens.sentAsWritten("SELECT * FROM \"s\".\"t\" WHERE a = '{fn x}' -- {oj y}"));
    }

    private static void refusedCustom(String sql, String why) {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
            () -> SqlTokens.checkCustomSql(sql, 10_000), sql);
        assertTrue(e.getMessage().startsWith("params.sql: ") && e.getMessage().contains(why), sql + " gave "
            + e.getMessage());
    }

    /** A custom SQL rule with an escape is refused when it is saved, and its filled-in SQL when it runs. */
    @Test
    void aCustomSqlRuleWithAnEscapeIsRefusedWhenSavedAndWhenRun() throws Exception {
        IllegalArgumentException saved = assertThrows(IllegalArgumentException.class,
            () -> custom("SELECT * FROM ${table} {fn xINTO leak}").check(context("postgresql", true, true), "admin"));
        assertTrue(saved.getMessage().contains("'{' and '}' are not allowed outside quotes"), saved.getMessage());
        Rule rule = custom("SELECT * FROM ${table} WHERE note = '{x}'").check(context("postgresql", true, true), "admin");
        RuleRunner runner = new RuleRunner(Duration.ofSeconds(5), 0, 1 << 20, true);
        SqlRules.Table table = new SqlRules.Table("sales", "orders");
        assertNull(runner.customSqlRefusal(rule, SqlDialect.POSTGRESQL,
            SqlRules.plan(rule, SqlDialect.POSTGRESQL, table, Map.of(), u -> null)));
        // A rule saved before the check refused braces: its SQL, filled in, is refused before it is sent
        rule.params().put("sql", "SELECT * FROM ${table} {fn xINTO leak}");
        SqlRules.Plan plan = SqlRules.plan(rule, SqlDialect.POSTGRESQL, table, Map.of(), u -> null);
        assertTrue(plan.failingSql().contains("\"sales\".\"orders\" {fn xINTO leak}"), plan.failingSql());
        assertEquals("The rule's SQL does not form a single query. Rewrite it as one SELECT.",
            runner.customSqlRefusal(rule, SqlDialect.POSTGRESQL, plan));
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

    /**
     * The second review: a number written straight before a name or a $ (1e$a$, 1_$a$, 1E'...') is read
     * by PostgreSQL 14 and older as the number and then a name or an E'' string, where this check
     * read a $-quoted or plain string, hiding a call to a refused function. PostgreSQL 15 and later
     * refuse such text; so does the check now, in every check of SQL a person wrote.
     */
    @Test
    void aNumberRunningIntoANameOrADollarIsRefused() {
        String why = "a number is followed straight away by a letter, a digit, _ or $";
        for (String hidden : List.of("SELECT 1e$a$, pg_terminate_backend(pid) AS x$a$ FROM pg_stat_activity",
            "SELECT 1_$a$, set_config('role', 'x', false) AS x$a$", "SELECT 1E'\\', pg_sleep(1) AS x, '' AS y",
            "SELECT 1$a$ ? $a$", "SELECT 1.5e$a$, pg_sleep(1) AS x$a$", "SELECT .5e$a$, pg_sleep(1) AS x$a$",
            "SELECT 1e+$a$, pg_sleep(1) AS x$a$", "SELECT 0x$a$, pg_sleep(1) AS x$a$", "SELECT 1__0", "SELECT 1e",
            "SELECT 1abc", "SELECT 0b2", "SELECT 1.5f", "SELECT 2returning")) {
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> SqlTokens.checkCustomSql(hidden, 10_000), hidden);
            assertTrue(e.getMessage().contains(why), hidden + " gave " + e.getMessage());
        }
        for (String hidden : List.of("INSERT INTO t SELECT 1e$a$, pg_terminate_backend(pid) AS x$a$ FROM pg_stat_activity",
            "CREATE TABLE t2 AS SELECT 1e$a$, set_config('standard_conforming_strings', 'off', false) AS x$a$",
            "UPDATE t SET c = 1$a$ {fn xROLE} $a$", "DELETE FROM t WHERE a = 1returning a")) {
            refusedAction(hidden, why);
        }
        // Numbers as PostgreSQL 16 writes them are read whole, as before
        for (String ok : List.of("SELECT 1e10, 1.5e-3, 2E+4, .5, 5., 1_000, 1_000.000_1, 0x1F, 0o17, 0b1010, 0x_FF",
            "SELECT a FROM t WHERE b < 1e3 AND c IN (1, 2) AND d BETWEEN 1 AND 2", "SELECT 1 AS e, 2 AS x",
            "SELECT 1::int, 2::text, a[1:2] FROM t", "SELECT $a$ 1e$b$ x $a$", "SELECT 1+2-3*4/5%6")) {
            assertEquals(ok, SqlTokens.checkCustomSql(ok, 10_000), ok);
        }
        assertEquals(List.of("1e10", "1.5e-3", "1_000", "0x1F"), SqlTokens.tokens("1e10, 1.5e-3, 1_000, 0x1F").stream()
            .filter(t -> t.kind() == SqlTokens.Kind.NUMBER).map(SqlTokens.Token::text).toList());
        // The tokenizer is what generated SQL is checked with too: a rule's own numbers still read as one statement
        assertTrue(SqlTokens.isSingleStatement("SELECT COUNT(*) FROM \"s\".\"t\" WHERE \"a\" < 1e3"));
    }

    /** The spaces beyond ASCII the third review named, and others like them. */
    private static final List<Character> OTHER_SPACES = java.util.stream.IntStream.of(0x00a0, 0x2000, 0x2001, 0x2002,
        0x2003, 0x2004, 0x2005, 0x2006, 0x2007, 0x2008, 0x2009, 0x200a, 0x200b, 0x2028, 0x2029, 0x202f, 0x205f, 0x3000,
        0xfeff).mapToObj(c -> (char) c).toList();

    /**
     * The third review: a space beyond ASCII before a trailing ; was read as part of the word before it
     * (security and U+2003, no refused word), then Java's strip() took it off with the ;, and the text sent
     * ended in the refused word. Now only PostgreSQL's own whitespace is taken off, the text sent must
     * read as the tokens checked, and such a space outside quotes and comments is refused, as
     * PostgreSQL reads it as part of a name.
     */
    @Test
    void aSpaceBeyondAsciiNeverHidesAWord() {
        String why = "a space that is not an ordinary space";
        for (char space : OTHER_SPACES) {
            String sp = String.valueOf(space);
            for (String action : List.of("ALTER TABLE t ENABLE ROW LEVEL SECURITY" + sp + ";",
                "ALTER TABLE t ENABLE ROW LEVEL SECURITY" + sp, sp + "ALTER TABLE t ENABLE ROW LEVEL SECURITY",
                "ALTER TABLE t FORCE ROW LEVEL security" + sp + " ;", sp + "TRUNCATE t", "TRUNCATE t" + sp + ";",
                "TRUNCATE" + sp + "t")) {
                IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                    () -> SqlTokens.checkAction(action, 10_000), action);
                assertTrue(e.getMessage().contains(why), (int) space + ": " + e.getMessage());
            }
            for (String custom : List.of("SELECT a FROM t FOR UPDATE" + sp + ";", sp + "SELECT 1", "SELECT a FROM t" + sp,
                "SELECT a FROM t" + sp + ";", "SELECT a" + sp + "FROM t")) {
                refusedCustom(custom, why);
            }
            // In strings, quoted names and comments it is text, as before
            String ok = "UPDATE t SET note = '" + sp + "' WHERE \"a" + sp + "b\" = 1 /* " + sp + " */";
            assertEquals(ok, SqlTokens.checkAction(ok, 10_000));
            assertEquals("SELECT '" + sp + "' AS x", SqlTokens.checkCustomSql("SELECT '" + sp + "' AS x", 10_000));
        }
        // PostgreSQL's own whitespace is still taken off either end, and the text returned is what was checked
        assertEquals("TRUNCATE t", SqlTokens.checkAction(" \t\r\n\f\u000bTRUNCATE t \t;\n ", 10_000));
        assertEquals("SELECT 1", SqlTokens.checkCustomSql("\u000b SELECT 1 ;\f", 10_000));
    }

    /**
     * The third review: housekeeping functions whose work survives a read-only transaction rolled back
     * (BRIN summaries, a GIN index's pending list), and functions that change the catalogue's
     * statistics, the server's caches or its write-ahead log, are refused in every check.
     */
    @Test
    void housekeepingThatWritesFromAReadOnlyTransactionIsRefused() {
        for (String f : List.of("brin_summarize_new_values('t_idx')", "brin_summarize_range('t_idx', 0)",
            "brin_desummarize_range('t_idx', 0)", "gin_clean_pending_list('t_idx')",
            "pg_clear_relation_stats('s', 't')", "pg_clear_attribute_stats('s', 't', 'a', false)",
            "pg_restore_relation_stats('schemaname', 's')", "pg_restore_attribute_stats('schemaname', 's')",
            "pg_set_relation_stats('s.t'::regclass, 1)", "pg_stat_reset()", "pg_stat_reset_shared('io')",
            "pg_prewarm('t')", "autoprewarm_dump_now()", "pg_wal_replay_pause()", "pg_truncate_visibility_map('t')",
            "heap_force_kill('t', ARRAY['(0,1)'::tid])", "pg_catalog.brin_summarize_new_values('t_idx')")) {
            refused("SELECT " + f);
            refusedAction("INSERT INTO t SELECT " + f, "is not allowed");
        }
        // The fourth review: copying a replication slot makes a permanent one, kept after a rollback
        for (String f : List.of("pg_copy_physical_replication_slot('admin_slot', 'evil_p2', false)",
            "pg_catalog.pg_copy_logical_replication_slot('admin_lslot', 'evil_l2')", "pg_sync_replication_slots()",
            "pg_replication_slot_advance('s', '0/0')")) {
            refused("SELECT " + f);
            refused("SELECT * FROM " + f);
            refusedAction("CREATE TABLE x AS SELECT " + f, "is not allowed");
        }
        // Sequences: refused where only reading is meant, taken by a sql node, which writes anyway
        refused("SELECT nextval('s')");
        refused("SELECT setval('s', 1)");
        assertEquals("INSERT INTO t VALUES (nextval('s'))", SqlTokens.checkAction("INSERT INTO t VALUES (nextval('s'))",
            10_000));
        // Names that only start alike are other names: a column called brin is quoted, as any refused name
        SqlTokens.checkCustomSql("SELECT \"gin\", pg_size_pretty(1) FROM t", 10_000);
    }

    /**
     * The second review: a custom rule's ${column} written inside a string or a comment was filled in
     * there too, so a column whose name holds a quote ended the string and added SQL that was never
     * checked. Placeholders now count only where they stand as SQL, are refused anywhere else when the
     * rule is saved, are filled in only where they stand as SQL, and the SQL as filled in is checked
     * again in full before it runs. Written in lower case only, as they are filled in.
     */
    @Test
    void placeholdersAreNamesWhereANameGoesAndTheFilledInSqlIsCheckedAgain() throws Exception {
        String where = "write ${table} and ${column} in lower case, as SQL, never inside quotes or a comment";
        for (String misplaced : List.of("SELECT * FROM ${table} WHERE '${column}' <> ''",
            "SELECT * FROM ${table} /* ${column} */", "SELECT * FROM ${table} WHERE 1=1 -- ${column}",
            "SELECT * FROM ${table} WHERE \"${column}\" IS NULL", "SELECT * FROM ${table} WHERE a = $q$${column}$q$",
            "SELECT * FROM ${table} WHERE x = '${COLUMN}'", "SELECT * FROM ${table} WHERE '${ column }' <> ''")) {
            refusedCustom(misplaced, where);
        }
        // Not in lower case, or not exactly as written: refused, as it would not be filled in
        for (String odd : List.of("SELECT * FROM ${TABLE}", "SELECT * FROM ${Table} x", "SELECT * FROM ${table } x")) {
            assertThrows(IllegalArgumentException.class, () -> SqlTokens.checkCustomSql(odd, 10_000), odd);
        }
        assertEquals(List.of(new SqlTokens.Placeholder(new SqlTokens.Span(14, 22), "table"),
            new SqlTokens.Placeholder(new SqlTokens.Span(29, 38), "column")),
            SqlTokens.placeholders("SELECT * FROM ${table} WHERE ${column} > 0 AND n = '${column}' /* ${table} */"));

        // Filled in only where they stand as SQL: a rule saved before the check refused the others leaves them be
        String crafted = "\"x'>''AND set_config('work_mem','9MB',false)>''AND'\"";
        assertEquals("SELECT * FROM \"s\".\"t\" WHERE '${column}' <> '' /* ${table} */",
            SqlRules.placeholders("SELECT * FROM ${table} WHERE '${column}' <> '' /* ${table} */", "\"s\".\"t\"", crafted));

        // A crafted name filled in where a name goes stays one name, and the rule may run
        RuleRunner runner = new RuleRunner(Duration.ofSeconds(5), 0, 1 << 20, true);
        Rule rule = new RuleDraft(URN, null, null, "custom_sql", null,
            Facts.JSON.readTree("{\"sql\":\"SELECT * FROM ${table} WHERE ${column} IS NULL\"}"), null, null, null, null,
            null).check(context("postgresql", true, true), "admin");
        Rule named = new Rule(rule.id(), rule.assetUrn(), "x'>''AND set_config('work_mem','9MB',false)>''AND'",
            rule.name(), rule.type(), rule.dimension(), rule.params(), rule.thresholdUnit(), rule.warnAbove(),
            rule.failAbove(), rule.actions(), rule.enabled(), rule.origin(), rule.originRef(), rule.createdBy(),
            rule.createdAt(), rule.updatedAt());
        SqlRules.Plan plan = SqlRules.plan(named, SqlDialect.POSTGRESQL, new SqlRules.Table("public", "lo_t"), Map.of(),
            u -> null);
        assertEquals("SELECT * FROM \"public\".\"lo_t\" WHERE " + crafted + " IS NULL", plan.failingSql());
        assertNull(runner.customSqlRefusal(named, SqlDialect.POSTGRESQL, plan),
            "a name, however odd, is a name, and a table may start lo_");
        // The reviewer's rule, saved before this check: its ${column} inside a string is not filled in
        rule.params().put("sql", "SELECT * FROM ${table} WHERE '${column}' <> ''");
        SqlRules.Plan old = SqlRules.plan(named, SqlDialect.POSTGRESQL, new SqlRules.Table("public", "t"), Map.of(),
            u -> null);
        assertEquals("SELECT * FROM \"public\".\"t\" WHERE '${column}' <> ''", old.failingSql());

        // The full check runs on the SQL as filled in: what was hidden is found
        String injected = "SELECT * FROM \"public\".\"t\" WHERE '\"x'>''AND set_config('work_mem','9MB',false)>''AND'\"' <> ''";
        assertNotNull(SqlTokens.checkFilledCustomSql(injected, List.of(new SqlTokens.Span(36, 85))));
        assertNotNull(SqlTokens.checkFilledCustomSql(injected, List.of()), "set_config is refused wherever it stands");
        String commented = "SELECT * FROM \"public\".\"t\" /* \"*/ WHERE pg_sleep(5) IS NULL /*\" */";
        assertNotNull(SqlTokens.checkFilledCustomSql(commented, List.of()));
        // A span that is not where a whole quoted name stands is refused, whatever it holds
        String fine = "SELECT * FROM \"public\".\"t\" WHERE \"a\" IS NULL";
        assertNull(SqlTokens.checkFilledCustomSql(fine, List.of(new SqlTokens.Span(14, 26), new SqlTokens.Span(33, 36))));
        for (SqlTokens.Span off : List.of(new SqlTokens.Span(15, 26), new SqlTokens.Span(14, 25), new SqlTokens.Span(27, 36),
            new SqlTokens.Span(33, 40), new SqlTokens.Span(0, 6), new SqlTokens.Span(40, 60))) {
            assertNotNull(SqlTokens.checkFilledCustomSql(fine, List.of(off)), off.toString());
        }
        // A filled-in name is never held to the function list; the author's own words still are
        assertNull(SqlTokens.checkFilledCustomSql("SELECT * FROM \"lo_t\"", List.of(new SqlTokens.Span(14, 20))));
        assertNotNull(SqlTokens.checkFilledCustomSql("SELECT * FROM \"lo_t\"", List.of()));
        assertNotNull(SqlTokens.checkFilledCustomSql("SELECT pg_sleep(1) FROM \"t\"", List.of(new SqlTokens.Span(24, 27))));
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
        assertEquals(true, Regexes.find(Pattern.compile("^a+$"), "aaa"));
        assertEquals(false, Regexes.find(Pattern.compile("^a+$"), "aab"));
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

package io.kilnmark.server.quality;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.kilnmark.kiln.plugins.jdbc.SqlDialect;

/** Rules without a database: validation, thresholds, row checks, regex suggestions, SQL and scores. */
class RulesUnitTest {

    private static final String URN = "urn:kilnmark:shop:sales.orders";

    private static RuleDraft.Context context(boolean sql, List<String> allow) {
        return new RuleDraft.Context() {
            @Override
            public Map<String, String> fields() {
                return Map.of("id", "int64", "email", "text", "amount", "decimal", "placed_at", "timestamp_tz",
                    "customer_id", "int64", "odd \"name\"]", "text");
            }

            @Override
            public String sourceKind() {
                return sql ? "postgresql" : "files";
            }

            @Override
            public boolean sql() {
                return sql;
            }

            @Override
            public Optional<Map<String, String>> fieldsOf(String urn) {
                return urn.equals("urn:kilnmark:shop:sales.customers") ? Optional.of(Map.of("id", "int64"))
                    : Optional.empty();
            }

            @Override
            public List<String> webhookAllowList() {
                return allow;
            }

            @Override
            public boolean customSqlEnabled() {
                return true;
            }
        };
    }

    private static JsonNode json(String s) {
        try {
            return Facts.JSON.readTree(s.replace('\'', '"'));
        } catch (Exception e) {
            throw new IllegalArgumentException(e);
        }
    }

    private static Rule rule(String type, String field, String params, Double warn, Double fail, String unit) {
        return new RuleDraft(URN, field, null, type, null, params == null ? null : json(params), unit, warn, fail, null,
            null).check(context(true, List.of()), "test");
    }

    @Test
    void draftsAreCheckedAndTaggedWithADimension() {
        Rule r = rule("not_null", "email", null, null, null, null);
        assertEquals(Dimension.COMPLETENESS, r.dimension());
        assertEquals("email not null", r.name());
        assertEquals(0, r.failAbove());
        assertEquals(Dimension.VALIDITY, rule("matches_pattern", "email", "{'regex':'^x'}", null, null, null).dimension());
        assertEquals(Dimension.INTEGRITY, rule("references", "customer_id",
            "{'asset':'urn:kilnmark:shop:sales.customers','field':'id'}", null, null, null).dimension());

        assertThrows(IllegalArgumentException.class, () -> rule("not_null", null, null, null, null, null));
        assertThrows(IllegalArgumentException.class, () -> rule("not_null", "nope", null, null, null, null));
        assertThrows(IllegalArgumentException.class, () -> rule("matches_pattern", "email", "{'regex':'(['}", null, null,
            null));
        assertThrows(IllegalArgumentException.class, () -> rule("in_range", "amount", "{'min':'ten'}", null, null, null));
        assertThrows(IllegalArgumentException.class, () -> rule("freshness", "email", "{'maxAgeHours':1}", null, null,
            null), "freshness needs a date or timestamp");
        assertThrows(IllegalArgumentException.class, () -> rule("not_null", "email", null, 0.5, 0.1, null),
            "warn above fail");
        assertThrows(IllegalArgumentException.class, () -> rule("not_null", "email", null, null, 1.5, "share"));
        assertThrows(IllegalArgumentException.class, () -> rule("references", "customer_id",
            "{'asset':'urn:kilnmark:other:x.y','field':'id'}", null, null, null), "another source");
        IllegalArgumentException files = assertThrows(IllegalArgumentException.class, () -> new RuleDraft(URN, null,
            null, "custom_sql", null, json("{'sql':'SELECT 1'}"), null, null, null, null, null)
            .check(context(false, List.of()), "t"));
        assertTrue(files.getMessage().contains("database"));
    }

    @Test
    void webhooksMustBeOnTheAllowList() {
        List<String> allow = List.of("https://hooks.example.com/kilnmark/", "http://127.0.0.1:47391/hooks/");
        assertTrue(RuleDraft.allowed("https://hooks.example.com/kilnmark/quality", allow));
        assertTrue(RuleDraft.allowed("HTTPS://HOOKS.example.com/kilnmark/x", allow));
        assertTrue(RuleDraft.allowed("http://127.0.0.1:47391/hooks/a", allow));
        assertFalse(RuleDraft.allowed("https://hooks.example.com/other", allow));
        assertFalse(RuleDraft.allowed("https://hooks.example.com.evil.example/kilnmark/", allow));
        assertFalse(RuleDraft.allowed("https://hooks.example.com@evil.example/kilnmark/", allow));
        assertFalse(RuleDraft.allowed("http://127.0.0.1:47392/hooks/a", allow), "another port");
        assertFalse(RuleDraft.allowed("file:///etc/passwd", allow));
        assertFalse(RuleDraft.allowed("not a url", allow));
        assertFalse(RuleDraft.allowed("https://hooks.example.com/kilnmark/", List.of()), "an empty list allows none");

        RuleDraft d = new RuleDraft(URN, "email", null, "not_null", null, null, null, null, null,
            new RuleDraft.ActionsDraft("https://evil.example/", true), null);
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> d.check(context(true, allow), "t"));
        assertTrue(e.getMessage().startsWith("actions.webhook:"), e.getMessage());
        Rule ok = new RuleDraft(URN, "email", null, "not_null", null, null, null, null, null,
            new RuleDraft.ActionsDraft("https://hooks.example.com/kilnmark/q", true), null).check(context(true, allow), "t");
        assertEquals("https://hooks.example.com/kilnmark/q", ok.actions().webhook());
        assertTrue(ok.actions().task());
    }

    @Test
    void thresholdsAsShareOrCount() {
        Rule share = rule("not_null", "email", null, 0.01, 0.05, "share");
        assertEquals(Rule.Status.PASS, share.status(1, 1000));
        assertEquals(Rule.Status.WARN, share.status(20, 1000));
        assertEquals(Rule.Status.FAIL, share.status(60, 1000));
        assertEquals(Rule.Status.PASS, share.status(0, 0));
        Rule count = rule("not_null", "email", null, 5.0, 10.0, "count");
        assertEquals(Rule.Status.PASS, count.status(5, 10));
        assertEquals(Rule.Status.WARN, count.status(6, 1_000_000));
        assertEquals(Rule.Status.FAIL, count.status(11, 1_000_000));
        Rule strict = rule("unique", "id", null, null, null, null);
        assertEquals(Rule.Status.FAIL, strict.status(1, 1_000_000), "fail above 0 by default");
        Rule fresh = rule("freshness", "placed_at", "{'maxAgeHours':24}", 0.5, 0.9, "share");
        assertEquals("count", fresh.thresholdUnit(), "whole-asset checks pass or fail");
        assertNull(fresh.warnAbove());
    }

    private static RowChecks.Evaluation check(Rule r, List<String> columns, List<List<String>> rows) {
        RowChecks.Check c = RowChecks.of(r, columns, Map.of("id", "int64", "email", "text", "amount", "decimal",
            "placed_at", "timestamp_tz"), 100L, Instant.parse("2026-10-10T00:00:00Z"), 2, "sample");
        for (List<String> row : rows) {
            c.row(row::get);
        }
        return c.finish();
    }

    @Test
    void rowChecksOnCellText() {
        List<String> cols = List.of("id", "email", "amount", "placed_at");
        List<List<String>> rows = List.of(
            List.of("1", "a@example.com", "10.50", "2026-10-09T10:00:00Z"),
            java.util.Arrays.asList("2", null, "-3", "2026-10-01T00:00:00Z"),
            List.of("2", " ", "1000", "2026-10-08T00:00:00Z"),
            List.of("4", "not-an-email", "99.99", "2026-10-09T23:00:00Z"));
        RowChecks.Evaluation nn = check(rule("not_null", "email", "{'blankIsMissing':true}", null, null, null), cols, rows);
        assertEquals(4, nn.total());
        assertEquals(2, nn.failing());
        assertEquals(2, nn.failingRows().rows().size());
        assertEquals(1, check(rule("not_null", "email", null, null, null, null), cols, rows).failing());
        RowChecks.Evaluation unique = check(rule("unique", "id", null, null, null, null), cols, rows);
        assertEquals(2, unique.failing(), "both rows with id 2");
        assertEquals(2, check(rule("matches_pattern", "email", "{'regex':'^[^@]+@[^@]+$'}", null, null, null), cols, rows)
            .failing(), "null is not checked; ' ' and not-an-email fail");
        assertEquals(2, check(rule("in_range", "amount", "{'min':0,'max':100}", null, null, null), cols, rows).failing());
        assertEquals(1, check(rule("in_range", "placed_at", "{'min':'2026-10-02'}", null, null, null), cols, rows)
            .failing());
        assertEquals(1, check(rule("allowed_values", "id", "{'values':['1','2']}", null, null, null), cols, rows)
            .failing(), "id 4 is not allowed");
        RowChecks.Evaluation fresh = check(rule("freshness", "placed_at", "{'maxAgeHours':2}", null, null, null), cols,
            rows);
        assertEquals(0, fresh.failing(), "latest is an hour old");
        assertEquals(1, check(rule("row_count", null, "{'min':10}", null, null, null), cols, rows).failing());
        RowChecks.Evaluation change = check(rule("row_count_change", null, "{'maxChange':0.5}", null, null, null), cols,
            rows);
        assertEquals(1, change.failing(), "100 to 4 rows");
        assertTrue(change.message().contains("-96%"), change.message());
    }

    @Test
    void suggestedRegexesMergeShapesThatDifferOnlyInLength() {
        List<ProfileFacts.Counted> postcodes = List.of(new ProfileFacts.Counted("AA9 9AA", 400),
            new ProfileFacts.Counted("A9 9AA", 300), new ProfileFacts.Counted("AA99 9AA", 200),
            new ProfileFacts.Counted("AA9A 9AA", 99), new ProfileFacts.Counted("aaaa", 1));
        Patterns.Suggestion s = Patterns.suggest(postcodes, Map.of(), 1000, false);
        assertEquals("^(\\p{Lu}{1,2}[0-9]{1,2} [0-9]\\p{Lu}{2}|\\p{Lu}{2}[0-9]\\p{Lu} [0-9]\\p{Lu}{2})$", s.regex());
        assertEquals(0.999, s.coverage(), 1e-9);
        Pattern p = Pattern.compile(s.regex());
        for (String ok : List.of("SW1A 1AA", "M1 1AE", "EC12 3AB", "B33 8TH")) {
            assertTrue(p.matcher(ok).matches(), ok);
        }
        assertFalse(p.matcher("abcd").matches());
        assertEquals("^[0-9]{4}\\-[0-9]{2}\\-[0-9]{2}$", Patterns.regex("9999-99-99", false));
        assertEquals("^\\p{Lu}\\p{Ll}+ \\p{Lu}\\p{Ll}+$", Patterns.regex("Aa+ Aa+", true));
        assertEquals("^\\p{Ll}{4}\\.\\p{Ll}{2}$", Patterns.regex("aaaa.aa", false));
        assertTrue(Pattern.compile(Patterns.regex("a… (cut)", false)).matcher("anything (cut)").find());
        // Letters of any alphabet: Zoë and Émile have the shape Aa+.
        assertTrue(Pattern.compile(Patterns.regex("Aa+", true)).matcher("Zoë").matches());
        assertTrue(Pattern.compile(Patterns.regex("Aaaaa", false)).matcher("Émile").matches());
        assertEquals("[[:upper:]][[:lower:]]+", Regexes.toPostgres("\\p{Lu}\\p{Ll}+"));
        // A plus sign in a detailed shape is a plus sign; in a collapsed one it is written \+.
        assertEquals("^\\+[0-9]{2} [0-9]{4}$", Patterns.regex("+99 9999", false));
        assertEquals("^[0-9]\\+[0-9]$", Patterns.regex("9+9", false));
        assertEquals("^\\+[0-9]+$", Patterns.regex("\\+9+", true));
        assertTrue(Pattern.compile(Patterns.regex("9+9", false)).matcher("1+1").matches());
        Patterns.Suggestion email = Patterns.suggest(List.of(new ProfileFacts.Counted("aaaa@aaaa.aaa", 10)),
            Map.of("email", 99L), 100, false);
        assertEquals("format email", email.basis());
        assertTrue(Pattern.compile(email.regex()).matcher("jane.doe@example.co.uk").matches());
    }

    @Test
    void sqlQuotesAwkwardNamesAndValues() throws Exception {
        Rule nn = new RuleDraft(URN, "odd \"name\"]", null, "allowed_values", null,
            Facts.JSON.readTree("{\"values\":[\"it's\",\"x\"]}"), null, null, null, null, null)
            .check(context(true, List.of()), "t");
        SqlRules.Plan pg = SqlRules.plan(nn, SqlDialect.POSTGRESQL, new SqlRules.Table("sa\"les", "or ders"),
            Map.of("odd \"name\"]", "text"), u -> null);
        assertEquals("SELECT COUNT(*), COALESCE(SUM(CASE WHEN t.\"odd \"\"name\"\"]\" IS NOT NULL AND "
            + "CAST(t.\"odd \"\"name\"\"]\" AS text) NOT IN ('it''s', 'x') THEN 1 ELSE 0 END), 0) "
            + "FROM \"sa\"\"les\".\"or ders\" t", pg.countSql());
        SqlRules.Plan ms = SqlRules.plan(nn, SqlDialect.SQL_SERVER, new SqlRules.Table("sales", "or]ders"),
            Map.of("odd \"name\"]", "text"), u -> null);
        assertTrue(ms.countSql().contains("t.[odd \"name\"]]]"), ms.countSql());
        assertTrue(ms.countSql().contains("[sales].[or]]ders] t"), ms.countSql());
        assertTrue(ms.countSql().contains("N'it''s'"));

        Rule regex = rule("matches_pattern", "email", "{'regex':'^[a-z]+$'}", null, null, null);
        assertNull(SqlRules.plan(regex, SqlDialect.SQL_SERVER, new SqlRules.Table("s", "t"), Map.of(), u -> null),
            "no regular expressions in SQL Server: streamed instead");
        Rule range = rule("in_range", "amount", "{'min':'1e3'}", null, null, null);
        assertTrue(SqlRules.plan(range, SqlDialect.POSTGRESQL, new SqlRules.Table("s", "t"), Map.of("amount", "decimal"),
            u -> null).countSql().contains("t.\"amount\" < 1e3"));
        assertThrows(IllegalArgumentException.class, () -> SqlDialect.POSTGRESQL.number("1; DROP TABLE t"));
        Rule custom = new RuleDraft(URN, "amount", null, "custom_sql", null,
            json("{'sql':'SELECT * FROM ${table} WHERE ${column} < 0'}"), null, null, null, null, null)
            .check(context(true, List.of()), "t");
        SqlRules.Plan c = SqlRules.plan(custom, SqlDialect.POSTGRESQL, new SqlRules.Table("s", "t"), Map.of(), u -> null);
        assertEquals("SELECT * FROM \"s\".\"t\" WHERE \"amount\" < 0", c.failingSql());
    }

    @Test
    void scoresRollUpWithReasons() {
        ObjectNode e = Facts.JSON.createObjectNode();
        List<Measures.Measure> measures = List.of(
            new Measures.Measure("email", Dimension.COMPLETENESS, 0.9, e, "10% null"),
            new Measures.Measure("email", Dimension.VALIDITY, 1.0, e, "fine"),
            new Measures.Measure("id", Dimension.UNIQUENESS, 1.0, e, "unique"),
            new Measures.Measure("", Dimension.VOLUME, 1.0, e, "steady"));
        List<Scores.RuleResult> results = List.of(new Scores.RuleResult("email", Dimension.VALIDITY, "email pattern",
            Rule.Status.FAIL, 200, 1000, false));
        List<Drift.Event> drift = List.of(new Drift.Event("id", "distribution", "distinct_count", "medium", "changed", e));
        List<Scores.Score> scores = Scores.of(measures, results, drift);
        Scores.Score emailValidity = scores.stream().filter(s -> s.field().equals("email")
            && s.dimension().equals("validity")).findFirst().orElseThrow();
        assertEquals(0.8, emailValidity.score(), 1e-9);
        assertTrue(emailValidity.reasons().get(0).contains("200 of 1,000 rows"));
        Scores.Score id = scores.stream().filter(s -> s.field().equals("id") && s.dimension().equals("uniqueness"))
            .findFirst().orElseThrow();
        assertEquals(0.85, id.score(), 1e-9);
        Scores.Score overall = scores.stream().filter(s -> s.field().isEmpty() && s.dimension().equals("overall"))
            .findFirst().orElseThrow();
        // completeness 0.9, validity 0.8, uniqueness 0.85, volume 1.0
        assertEquals((0.9 + 0.8 + 0.85 + 1.0) / 4, overall.score(), 1e-4);
        assertTrue(overall.reasons().get(0).startsWith("Validity 80%"), overall.reasons().toString());
    }
}

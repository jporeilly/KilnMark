package io.kilnmark.server.quality;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.SQLTimeoutException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import io.kilnmark.kiln.Pipeline;
import io.kilnmark.kiln.RunResult;
import io.kilnmark.kiln.Runner;
import io.kilnmark.kiln.StepSpec;
import io.kilnmark.kiln.connect.AssetInfo;
import io.kilnmark.kiln.connect.Connector;
import io.kilnmark.kiln.connect.SourceConfig;
import io.kilnmark.kiln.plugins.jdbc.JdbcConnector;
import io.kilnmark.kiln.plugins.jdbc.SqlDialect;
import io.kilnmark.server.quality.RowChecks.Evaluation;

/**
 * Runs an asset's rules where the data is. On a database, each rule is SQL run in the database,
 * in its own read-only transaction (where the database enforces one) with a time limit, and
 * rolled back afterwards. What the database cannot run (regular expressions on SQL Server), and
 * every rule on files, is evaluated by Kiln in one streaming pass over the data.
 */
public final class RuleRunner {

    /** The asset the rules are about, and how to reach it. */
    public record Target(Connector connector, SourceConfig source, AssetInfo asset, Map<String, String> kinds,
                         Long previousRows, Function<String, SqlRules.Table> others) {
    }

    private final Duration queryTimeout;
    private final int keepFailing;
    private final long memoryLimitBytes;

    public RuleRunner(Duration queryTimeout, int keepFailing, long memoryLimitBytes) {
        this.queryTimeout = queryTimeout;
        this.keepFailing = keepFailing;
        this.memoryLimitBytes = memoryLimitBytes;
    }

    /** One evaluation per rule, in the order given. */
    public List<Evaluation> run(List<Rule> rules, Target t, Instant now) throws InterruptedException {
        Map<Integer, Evaluation> out = new HashMap<>();
        List<Integer> streamed = new ArrayList<>();
        if (t.connector() instanceof JdbcConnector jdbc) {
            SqlDialect d = jdbc.dialect();
            SqlRules.Table table = new SqlRules.Table(t.asset().hints().get("schema"), t.asset().hints().get("table"));
            List<SqlRules.Plan> plans = new ArrayList<>();
            for (int i = 0; i < rules.size(); i++) {
                SqlRules.Plan p;
                try {
                    p = SqlRules.plan(rules.get(i), d, table, t.kinds(), t.others());
                } catch (RuntimeException e) {
                    out.put(i, Evaluation.error("sql", e.getMessage()));
                    plans.add(null);
                    continue;
                }
                plans.add(p);
                if (p == null) {
                    streamed.add(i);
                }
            }
            if (plans.stream().anyMatch(java.util.Objects::nonNull)) {
                try (Connection c = jdbc.connect(t.source())) {
                    c.setAutoCommit(false);
                    c.setReadOnly(true);
                    for (int i = 0; i < rules.size(); i++) {
                        if (plans.get(i) != null) {
                            out.put(i, sql(c, d, rules.get(i), plans.get(i), t, now));
                        }
                    }
                } catch (SQLException e) {
                    for (int i = 0; i < rules.size(); i++) {
                        if (plans.get(i) != null) {
                            out.putIfAbsent(i, Evaluation.error("sql", "Cannot connect to the source: " + e.getMessage()));
                        }
                    }
                }
            }
        } else {
            for (int i = 0; i < rules.size(); i++) {
                streamed.add(i);
            }
        }
        if (!streamed.isEmpty()) {
            List<Rule> some = streamed.stream().map(rules::get).toList();
            List<Evaluation> results = stream(some, t, now);
            for (int k = 0; k < streamed.size(); k++) {
                out.put(streamed.get(k), results.get(k));
            }
        }
        List<Evaluation> ordered = new ArrayList<>();
        for (int i = 0; i < rules.size(); i++) {
            ordered.add(out.get(i));
        }
        return ordered;
    }

    private Evaluation sql(Connection c, SqlDialect d, Rule rule, SqlRules.Plan plan, Target t, Instant now) {
        try {
            for (String s : d.readOnly()) {
                try (Statement st = c.createStatement()) {
                    st.execute(s);
                }
            }
            Evaluation e;
            try (Statement st = c.createStatement()) {
                st.setQueryTimeout((int) Math.max(1, queryTimeout.toSeconds()));
                try (ResultSet rs = st.executeQuery(plan.countSql())) {
                    if (!rs.next()) {
                        return Evaluation.error("sql", "The check's query returned no row");
                    }
                    e = switch (plan.shape()) {
                        case TOTAL_AND_FAILING -> new Evaluation(rs.getLong(1), rs.getLong(2), "sql", plan.failingSql(),
                            null, null, null);
                        case LATEST -> RowChecks.freshness(latest(rs, t.kinds().get(rule.field())),
                            rule.params().path("maxAgeHours").asDouble(), now, "sql");
                        case ROWS -> rule.type() == RuleType.ROW_COUNT
                            ? RowChecks.rowCount(rs.getLong(1), rule.params(), "sql")
                            : RowChecks.rowCountChange(rs.getLong(1), t.previousRows(),
                                rule.params().path("maxChange").asDouble(), "sql");
                    };
                }
            }
            if (e.failing() > 0 && plan.failingSql() != null && keepFailing > 0) {
                e = new Evaluation(e.total(), e.failing(), e.method(), e.failingQuery(),
                    failingRows(c, d.limit(plan.failingSql(), keepFailing)), e.message(), null);
            }
            return e;
        } catch (SQLException ex) {
            // PostgreSQL reports a cancelled statement as 57014, SQL Server a timeout as HY008.
            if (ex instanceof SQLTimeoutException || "57014".equals(ex.getSQLState()) || "HY008".equals(ex.getSQLState())) {
                return Evaluation.error("sql", "The check took longer than " + queryTimeout.toSeconds() + " seconds and "
                    + "was stopped. Narrow the rule, or raise kilnmark.quality.query-timeout.");
            }
            return Evaluation.error("sql", "The check's query failed: " + ex.getMessage());
        } finally {
            try {
                c.rollback(); // nothing a check does is ever kept
            } catch (SQLException ex) {
                // the connection is closed by the caller either way
            }
        }
    }

    private RowChecks.FailingRows failingRows(Connection c, String sql) throws SQLException {
        try (Statement st = c.createStatement()) {
            st.setQueryTimeout((int) Math.max(1, queryTimeout.toSeconds()));
            st.setMaxRows(keepFailing);
            try (ResultSet rs = st.executeQuery(sql)) {
                ResultSetMetaData md = rs.getMetaData();
                List<String> columns = new ArrayList<>();
                for (int i = 1; i <= md.getColumnCount(); i++) {
                    columns.add(md.getColumnLabel(i));
                }
                List<List<String>> rows = new ArrayList<>();
                while (rs.next() && rows.size() < keepFailing) {
                    List<String> row = new ArrayList<>();
                    for (int i = 1; i <= columns.size(); i++) {
                        String v = rs.getString(i);
                        row.add(v != null && v.length() > 1000 ? v.substring(0, 1000) + "…" : v);
                    }
                    rows.add(row);
                }
                return new RowChecks.FailingRows(columns, rows);
            }
        }
    }

    /** The latest value as an instant; timestamps without a zone are taken as UTC, as everywhere in Kilnmark. */
    private static Instant latest(ResultSet rs, String kind) throws SQLException {
        return switch (kind == null ? "" : kind) {
            case "timestamp_tz" -> {
                OffsetDateTime v = rs.getObject(1, OffsetDateTime.class);
                yield v == null ? null : v.toInstant();
            }
            case "date" -> {
                LocalDate v = rs.getObject(1, LocalDate.class);
                yield v == null ? null : v.atStartOfDay(ZoneOffset.UTC).toInstant();
            }
            default -> {
                LocalDateTime v = rs.getObject(1, LocalDateTime.class);
                yield v == null ? null : v.toInstant(ZoneOffset.UTC);
            }
        };
    }

    private List<Evaluation> stream(List<Rule> rules, Target t, Instant now) throws InterruptedException {
        RuleStreamStep check = new RuleStreamStep(rules, t.kinds(), t.previousRows(), keepFailing, now);
        String urn = new io.kilnmark.graph.Urn(t.source().name(), t.asset().path()).toString();
        StepSpec read = t.connector().reader(t.source(), t.asset(), new Connector.ReadOptions(urn, 0, 8192));
        Pipeline p = new Pipeline("quality-" + t.asset().path(), List.of(read, new StepSpec("rules", check, Map.of())));
        RunResult r = new Runner(memoryLimitBytes, 4).run(p, event -> { });
        if (!r.succeeded()) {
            List<Evaluation> failed = new ArrayList<>();
            rules.forEach(x -> failed.add(Evaluation.error("stream", "Reading the data failed: " + r.error())));
            return failed;
        }
        return check.evaluations();
    }
}

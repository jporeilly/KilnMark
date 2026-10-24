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
 *
 * <p>Custom SQL runs only when the administrator turned it on, only on PostgreSQL, never under a
 * login that is a superuser or a member of {@code pg_read_server_files},
 * {@code pg_write_server_files}, {@code pg_execute_server_program} or {@code pg_signal_backend},
 * and only when the generated query is exactly one statement.
 */
public final class RuleRunner {

    /** The asset the rules are about, and how to reach it. */
    public record Target(Connector connector, SourceConfig source, AssetInfo asset, Map<String, String> kinds,
                         Long previousRows, Function<String, SqlRules.Table> others) {
    }

    private final Duration queryTimeout;
    private final int keepFailing;
    private final long memoryLimitBytes;
    private final boolean customSql;

    public RuleRunner(Duration queryTimeout, int keepFailing, long memoryLimitBytes) {
        this(queryTimeout, keepFailing, memoryLimitBytes, false);
    }

    public RuleRunner(Duration queryTimeout, int keepFailing, long memoryLimitBytes, boolean customSql) {
        this.queryTimeout = queryTimeout;
        this.keepFailing = keepFailing;
        this.memoryLimitBytes = memoryLimitBytes;
        this.customSql = customSql;
    }

    /** Why custom SQL may not run here, or null when it may (the login is checked separately). */
    String customSqlRefusal(Rule rule, SqlDialect d, SqlRules.Plan plan) {
        if (rule.type() != RuleType.CUSTOM_SQL) {
            return null;
        }
        if (!customSql) {
            return "Custom SQL rules are turned off on this server (kilnmark.quality.custom-sql).";
        }
        if (d != SqlDialect.POSTGRESQL) {
            return "Custom SQL rules run only on PostgreSQL sources, where they can be held to a read-only "
                + "transaction.";
        }
        // The steward's SQL (as filled in) must balance on its own, so it cannot close the query it is
        // placed in, and the whole query must be one statement.
        if (plan == null || !SqlTokens.isSingleStatement(plan.failingSql()) || !SqlTokens.isSingleStatement(plan.countSql())) {
            return "The rule's SQL does not form a single query. Rewrite it as one SELECT.";
        }
        return null;
    }

    /** Roles of the login that make custom SQL unsafe however it is written. */
    static final String PRIVILEGED_LOGIN = """
        SELECT r.rolsuper OR pg_has_role(current_user, 'pg_read_server_files', 'MEMBER')
            OR pg_has_role(current_user, 'pg_write_server_files', 'MEMBER')
            OR pg_has_role(current_user, 'pg_execute_server_program', 'MEMBER')
            OR pg_has_role(current_user, 'pg_signal_backend', 'MEMBER')
        FROM pg_roles r WHERE r.rolname = current_user""";

    static final String PRIVILEGED_REFUSAL = "Custom SQL does not run under this source's login: it is a superuser "
        + "or may read or write the server's files, run programs or stop other sessions. Give the source a read-only "
        + "login without those rights.";

    private static boolean privileged(Connection c) throws SQLException {
        try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery(PRIVILEGED_LOGIN)) {
            return !rs.next() || rs.getBoolean(1);
        } finally {
            c.rollback();
        }
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
                String refusal = customSqlRefusal(rules.get(i), d, p);
                if (refusal != null) {
                    out.put(i, Evaluation.error("sql", refusal));
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
                    Boolean unsafeLogin = null;
                    for (int i = 0; i < rules.size(); i++) {
                        if (plans.get(i) == null) {
                            continue;
                        }
                        if (rules.get(i).type() == RuleType.CUSTOM_SQL) {
                            if (unsafeLogin == null) {
                                unsafeLogin = privileged(c);
                            }
                            if (unsafeLogin) {
                                out.put(i, Evaluation.error("sql", PRIVILEGED_REFUSAL));
                                continue;
                            }
                        }
                        out.put(i, sql(c, d, rules.get(i), plans.get(i), t, now));
                    }
                } catch (SQLException e) {
                    for (int i = 0; i < rules.size(); i++) {
                        if (plans.get(i) != null) {
                            out.putIfAbsent(i, Evaluation.error("sql", "Cannot connect to the source to check the rule.",
                                e.getMessage()));
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
            List<String> setup = new ArrayList<>(d.readOnly());
            setup.addAll(d.timeout(queryTimeout.toMillis()));
            for (String s : setup) {
                try (Statement st = c.createStatement()) {
                    st.execute(s);
                }
            }
            Evaluation e;
            try (Statement st = c.createStatement()) {
                st.setQueryTimeout((int) Math.max(1, queryTimeout.toSeconds()));
                try (ResultSet rs = st.executeQuery(plan.countSql())) {
                    if (!rs.next()) {
                        return Evaluation.error("sql", "The check's query returned no row.");
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
            return Evaluation.error("sql", "The check's query failed in the database.", ex.getMessage());
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
                        row.add(v != null && v.length() > RowChecks.CELL ? v.substring(0, RowChecks.CELL) + "…" : v);
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
        java.util.concurrent.atomic.AtomicBoolean sampled = new java.util.concurrent.atomic.AtomicBoolean();
        RunResult r = new Runner(memoryLimitBytes, 4).run(p, event -> {
            if (event.kind() == io.kilnmark.kiln.MetadataEvent.Kind.ROW_COUNT
                && Boolean.parseBoolean(event.attributes().get("sampled"))) {
                sampled.set(true); // the reader went through TABLESAMPLE: results are estimates
            }
        });
        if (!r.succeeded()) {
            List<Evaluation> failed = new ArrayList<>();
            rules.forEach(x -> failed.add(Evaluation.error("stream", "Reading the data failed.", r.error())));
            return failed;
        }
        return check.evaluations().stream().map(e -> sampled.get() && e.error() == null ? e.asEstimate() : e).toList();
    }
}

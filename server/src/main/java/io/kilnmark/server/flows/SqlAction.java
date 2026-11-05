package io.kilnmark.server.flows;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import io.kilnmark.graph.store.Records;
import io.kilnmark.kiln.connect.Connector;
import io.kilnmark.kiln.connect.SourceConfig;
import io.kilnmark.kiln.plugins.jdbc.JdbcConnector;
import io.kilnmark.kiln.plugins.jdbc.PostgresConnector;
import io.kilnmark.kiln.plugins.jdbc.SqlDialect;
import io.kilnmark.kiln.yaml.FlowDefinition;
import io.kilnmark.server.pipelines.PipelineSources;
import io.kilnmark.server.quality.RuleRunner;
import io.kilnmark.server.quality.SqlTokens;
import io.kilnmark.server.sources.CommonSettings;

/**
 * A flow's sql node (ADR 0028): statements a designer wrote, run on a PostgreSQL source that allows
 * both SQL and writing, in one transaction with a statement timeout, under a login that is not a
 * superuser and cannot reach files, programs or other databases. It never returns rows; it reports
 * how many its statements changed. Its messages never quote a statement or what the database said,
 * either of which can hold values.
 */
final class SqlAction {

    /** The only kind of source a sql node runs on, for now. */
    static final String KIND = "postgresql";

    /** How a sql node ended: rows changed when it succeeded, else why it did not, in Kilnmark's words. */
    record Outcome(boolean succeeded, long rowsAffected, String message) {

        static Outcome failed(String message) {
            return new Outcome(false, 0, message);
        }
    }

    /**
     * Lets a flow stop a sql node that is running: the statement running is cancelled, and no further
     * statement runs nor the transaction commits, so it rolls back.
     */
    static final class Control {
        private final AtomicReference<Statement> current = new AtomicReference<>();
        private final AtomicBoolean stopped = new AtomicBoolean();
        /** Held while committing, so a stop comes either before the commit (rolled back) or after it. */
        private final Object commit = new Object();

        void stop() {
            synchronized (commit) {
                stopped.set(true);
            }
            Statement st = current.get();
            if (st != null) {
                try {
                    st.cancel();
                } catch (SQLException e) {
                    // it has finished, or the connection has gone: either way nothing more runs
                }
            }
        }

        boolean stopped() {
            return stopped.get();
        }
    }

    private SqlAction() {
    }

    /** Why a source cannot run a sql node, or null when it can. */
    static String sourceRefusal(String node, Records.Source s) {
        if (!KIND.equals(s.kind())) {
            return "Node " + node + ": SQL runs only on PostgreSQL sources for now, and " + s.name() + " is a " + s.kind()
                + " source";
        }
        if (!CommonSettings.allowsSql(s.settings())) {
            return "Node " + node + ": " + s.name() + " does not allow SQL a person writes. A designer or an admin turns on "
                + "Allow SQL (" + CommonSettings.ALLOW_SQL + ") for it";
        }
        if (!CommonSettings.allowsWriting(s.settings())) {
            return "Node " + node + ": " + s.name() + " does not allow writing, and a sql node changes data. A designer or "
                + "an admin turns on Allow writing (" + CommonSettings.ALLOW_WRITING + ") for it";
        }
        return null;
    }

    /** Each statement's problem, as {@code statement n: why}; empty when every one may run. */
    static List<String> statementProblems(List<String> statements) {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < statements.size(); i++) {
            try {
                SqlTokens.checkAction(statements.get(i), FlowDefinition.MAX_STATEMENT);
            } catch (IllegalArgumentException e) {
                out.add("statement " + (i + 1) + ": " + e.getMessage());
            }
        }
        return out;
    }

    /**
     * Runs the node's statements on its source, checking everything again now: the source and its
     * settings may have changed since the flow was saved. {@code control} lets the flow stop it.
     */
    static Outcome run(FlowDefinition.Node node, PipelineSources sources, Duration timeout, Control control) {
        String id = node.id();
        Records.Source s = sources.source(node.source()).orElse(null);
        if (s == null) {
            return Outcome.failed("Node " + id + ": there is no saved source called '" + node.source() + "'");
        }
        String refused = sourceRefusal(id, s);
        if (refused != null) {
            return Outcome.failed(refused);
        }
        List<String> problems = statementProblems(node.statements());
        if (!problems.isEmpty()) {
            return Outcome.failed("Node " + id + " " + problems.getFirst());
        }
        Connector connector = sources.connector(s.kind()).orElse(null);
        if (!(connector instanceof JdbcConnector jdbc) || !SqlDialect.POSTGRESQL.equals(jdbc.dialect())) {
            return Outcome.failed("Node " + id + ": this server cannot run SQL on " + s.name());
        }
        SourceConfig config = new SourceConfig(s.name(), s.kind(), s.settings(), sources.secret(s));
        // The URL's rules again: a source saved before a rule existed (preferQueryMode, readOnlyMode) keeps it
        if (!jdbc.validate(config).isEmpty()) {
            return Outcome.failed("Node " + id + ": " + s.name() + "'s settings are no longer accepted; open the source "
                + "and save it again to see why");
        }
        String unencrypted = sources.hosts().encryptionRefusal(connector, config);
        if (unencrypted != null) {
            return Outcome.failed("Node " + id + ": " + unencrypted);
        }
        try {
            sources.hosts().check(connector, config);
        } catch (IllegalArgumentException e) {
            return Outcome.failed("Node " + id + ": " + s.name() + " is not on a host this server may reach now");
        }
        try (Connection c = jdbc.connect(config)) {
            c.setAutoCommit(false);
            // Each statement goes as one prepared statement, which PostgreSQL refuses to read as several:
            // never over the simple protocol, which would run whatever the text holds.
            if (c.unwrap(org.postgresql.PGConnection.class).getPreferQueryMode()
                == org.postgresql.jdbc.PreferQueryMode.SIMPLE) {
                return Outcome.failed("Node " + id + ": " + s.name() + " sends SQL over the simple protocol "
                    + "(preferQueryMode=simple), which a sql node never uses. Remove that setting from the source");
            }
            String login = RuleRunner.privileged(c);
            if (login != null) {
                return Outcome.failed("Node " + id + ": " + forNode(login));
            }
            return statements(c, node, jdbc.dialect(), timeout, control);
        } catch (SQLException e) {
            // Not the message: a driver can quote the URL. The state says enough to look further.
            return Outcome.failed("Node " + id + ": could not connect to " + s.name() + " (SQLSTATE " + e.getSQLState()
                + ")");
        }
    }

    /** Runs the statements in order in one transaction: all of them change the database, or none does. */
    private static Outcome statements(Connection c, FlowDefinition.Node node, SqlDialect d, Duration timeout,
                                      Control control) throws SQLException {
        long rows = 0;
        int n = 0;
        try {
            for (String q : d.timeout(timeout.toMillis())) {
                try (Statement st = c.createStatement()) {
                    st.execute(q);
                }
            }
            // Backslashes in plain strings are plain characters, as the check reads them, whatever the
            // database, the login or the URL says.
            PostgresConnector.conformingStrings(c);
            for (String sql : node.statements()) {
                if (control.stopped()) {
                    c.rollback();
                    return Outcome.failed(STOPPED);
                }
                n++;
                // An earlier statement may have turned it off (through pg_settings, say): the check, and the
                // driver's split, read this one with it on, so it runs only with it on.
                try {
                    PostgresConnector.requireConformingStrings(c);
                } catch (SQLException e) {
                    c.rollback();
                    return Outcome.failed("Node " + node.id() + ": statement " + n + " of " + node.statements().size()
                        + " was not run: standard_conforming_strings was off, so it could not be read as it was "
                        + "checked. Nothing was changed");
                }
                try (java.sql.PreparedStatement st = c.prepareStatement(
                    SqlTokens.checkAction(sql, FlowDefinition.MAX_STATEMENT))) {
                    control.current.set(st);
                    st.setQueryTimeout((int) Math.max(1, timeout.toSeconds()));
                    st.execute();
                    int changed = st.getUpdateCount();
                    rows += Math.max(0, changed);
                } finally {
                    control.current.set(null);
                }
            }
            synchronized (control.commit) {
                if (control.stopped()) {
                    c.rollback();
                    return Outcome.failed(STOPPED);
                }
                c.commit();
            }
            return new Outcome(true, rows, "Ran " + n + (n == 1 ? " statement" : " statements") + " on "
                + node.source() + "; " + rows + (rows == 1 ? " row" : " rows") + " changed");
        } catch (SQLException e) {
            c.rollback();
            if (control.stopped()) {
                return Outcome.failed(STOPPED);
            }
            // What the database said can quote values: only where it stopped, and its state.
            return Outcome.failed("Node " + node.id() + ": statement " + n + " of " + node.statements().size()
                + " failed (SQLSTATE " + e.getSQLState() + "), so none of them changed anything: they run in one "
                + "transaction");
        }
    }

    /** How a node the flow stopped ends. */
    static final String STOPPED = "Stopped with the flow; nothing was changed, as its statements run in one transaction";

    /** A login refusal from the custom SQL check, worded for a sql node. */
    static String forNode(String refusal) {
        return refusal.replace("Custom SQL does not run", "SQL does not run")
            .replace("custom SQL rules", "sql nodes").replace("custom SQL", "SQL")
            .replace("a read-only login", "a login limited to what its SQL needs");
    }
}

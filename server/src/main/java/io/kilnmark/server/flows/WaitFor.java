package io.kilnmark.server.flows;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

import org.jboss.logging.Logger;

import io.kilnmark.graph.store.Records;
import io.kilnmark.kiln.connect.Connector;
import io.kilnmark.kiln.connect.ConnectorException;
import io.kilnmark.kiln.connect.SourceConfig;
import io.kilnmark.kiln.plugins.files.FileSourceConnector;
import io.kilnmark.kiln.plugins.jdbc.JdbcConnector;
import io.kilnmark.kiln.plugins.jdbc.PostgresConnector;
import io.kilnmark.kiln.plugins.jdbc.SqlDialect;
import io.kilnmark.kiln.yaml.FlowDefinition;
import io.kilnmark.server.pipelines.PipelineSources;
import io.kilnmark.server.quality.RuleRunner;
import io.kilnmark.server.quality.SqlTokens;
import io.kilnmark.server.sources.CommonSettings;

/**
 * One look by a flow's {@code wait-for} node (ADR 0041, docs/design/flow-controls.md C2): how many
 * files below a file or object-store source's folder match its pattern, or whether its query is
 * true. The node looks again every so often until it is ready or gives up; between looks it holds
 * no connection.
 *
 * <p>A query is SQL a person wrote (ADR 0028): it runs only on a PostgreSQL source with Allow SQL
 * on, checked as custom SQL rules are, on a read-only connection with a statement timeout, under a
 * login that is not a superuser. Only whether its first value is true is kept; nothing it returns is
 * stored or shown. Messages never quote the query, a value, or what the source or its driver said.
 */
final class WaitFor {

    private static final Logger LOG = Logger.getLogger(WaitFor.class);

    /** How long a wait-for node's query may run (docs/design/flow-controls.md C2). */
    static final Duration QUERY_TIMEOUT = Duration.ofSeconds(30);

    /**
     * One look. {@code refused} is set when the node cannot look at all and never will as things are
     * (the source has gone, or no longer allows SQL): the node fails at once. {@code problem} is set
     * when this look could not be made (the source did not answer): the node looks again later.
     * Otherwise {@code found} is how many files matched, or 1 for a true query and 0 for a false one.
     */
    record Look(int found, String problem, String refused) {
        static Look found(int n) {
            return new Look(n, null, null);
        }

        static Look problem(String why) {
            return new Look(0, why, null);
        }

        static Look refused(String why) {
            return new Look(0, null, why);
        }
    }

    private WaitFor() {
    }

    /**
     * What is wrong with a wait-for node on this server, or null: its source must exist and be a file
     * or object-store source to wait for a file, or a PostgreSQL source that allows SQL to wait on a
     * query, which must be one read-only SELECT (ADR 0028).
     */
    static String refusal(FlowDefinition.Node n, PipelineSources sources) {
        Optional<Records.Source> src = sources == null ? Optional.empty() : sources.source(n.source());
        if (src.isEmpty()) {
            return "Node " + n.id() + " waits on the source '" + n.source() + "', which does not exist";
        }
        if (n.until().file() != null) {
            Optional<Connector> c = sources.connector(src.get().kind());
            if (c.isEmpty() || !"files".equals(c.get().category())) {
                return "Node " + n.id() + " waits for a file, but " + n.source() + " is not a file or object-store source";
            }
            return null;
        }
        if (!SqlAction.KIND.equals(src.get().kind())) {
            return "Node " + n.id() + ": a query runs only on PostgreSQL sources for now, and " + n.source() + " is a "
                + src.get().kind() + " source";
        }
        if (!CommonSettings.allowsSql(src.get().settings())) {
            return "Node " + n.id() + ": " + n.source() + " does not allow SQL a person writes. A designer or an admin "
                + "turns on Allow SQL (" + CommonSettings.ALLOW_SQL + ") for it";
        }
        return queryProblem(n);
    }

    /** What is wrong with a wait-for node's query, as a custom SQL rule's is checked, or null. */
    static String queryProblem(FlowDefinition.Node n) {
        if (n.until().query() == null) {
            return null;
        }
        try {
            SqlTokens.checkCustomSql(n.until().query(), FlowDefinition.MAX_STATEMENT);
            return null;
        } catch (IllegalArgumentException e) {
            return "Node " + n.id() + "'s query " + e.getMessage().replaceFirst("^params\\.sql: ", "")
                .replace("custom rules only read", "a wait-for query only reads");
        }
    }

    /** Looks once: counts the matching files, or runs the query. */
    static Look look(FlowDefinition.Node n, PipelineSources sources, Duration connectTimeout) {
        Optional<Records.Source> src = sources == null ? Optional.empty() : sources.source(n.source());
        if (src.isEmpty()) {
            return Look.refused("Node " + n.id() + ": there is no saved source called " + n.source());
        }
        String refused = refusal(n, sources);
        if (refused != null) {
            return Look.refused(refused);
        }
        Optional<Connector> found = sources.connector(src.get().kind());
        if (found.isEmpty()) {
            return Look.refused("Node " + n.id() + ": this server has no connector for " + n.source());
        }
        SourceConfig config = new SourceConfig(src.get().name(), src.get().kind(), src.get().settings(),
            sources.secret(src.get()));
        if (sources.hosts().encryptionRefusal(found.get(), config) != null) {
            return Look.refused("Node " + n.id() + ": this server connects to sources only over encrypted connections, "
                + "and " + n.source() + " is not set up for one");
        }
        if (sources.hosts().refusal(found.get(), config) != null) {
            return Look.refused("Node " + n.id() + ": " + n.source() + " is not on a host this server may reach now");
        }
        return n.until().file() != null ? files(n, found.get(), config, connectTimeout)
            : query(n, found.get(), config);
    }

    /**
     * How many files match the pattern: the folder it names is listed within {@code timeout}, as the
     * source's include and exclude patterns keep it, and each path (below the source's own prefix,
     * as the pattern is written) matched whole, {@code *} and {@code ?} never crossing a {@code /}.
     * Only names are read.
     */
    private static Look files(FlowDefinition.Node n, Connector c, SourceConfig config, Duration timeout) {
        if (!(c instanceof FileSourceConnector fs)) {
            return Look.refused("Node " + n.id() + ": this server cannot list the files of " + n.source());
        }
        String pattern = n.until().file().strip();
        Pattern match = glob(pattern);
        int slash = pattern.lastIndexOf('/');
        try {
            int count = 0;
            for (String path : fs.filesBelow(config, slash < 0 ? "" : pattern.substring(0, slash), timeout)) {
                if (match.matcher(path).matches()) {
                    count++;
                }
            }
            return Look.found(count);
        } catch (ConnectorException | RuntimeException e) {
            LOG.debugf("wait-for %s: could not list %s (%s)", n.id(), n.source(), e.getClass().getSimpleName());
            return Look.problem("Could not list the files on " + n.source());
        }
    }

    /** A file pattern as a whole-path match: {@code *} any characters but {@code /}, {@code ?} one. */
    static Pattern glob(String pattern) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < pattern.length(); i++) {
            char ch = pattern.charAt(i);
            if (ch == '*') {
                out.append("[^/]*");
            } else if (ch == '?') {
                out.append("[^/]");
            } else {
                out.append(Pattern.quote(String.valueOf(ch)));
            }
        }
        return Pattern.compile(out.toString());
    }

    /**
     * Runs the query on a read-only connection with a statement timeout: true when its first row's
     * first value is true. Rolled back whatever it did; nothing it returns is kept.
     */
    private static Look query(FlowDefinition.Node n, Connector c, SourceConfig config) {
        if (!(c instanceof JdbcConnector jdbc) || !SqlDialect.POSTGRESQL.equals(jdbc.dialect())) {
            return Look.refused("Node " + n.id() + ": this server cannot run SQL on " + n.source());
        }
        // The URL's rules again: a source saved before a rule existed (preferQueryMode, readOnlyMode) keeps it
        if (!jdbc.validate(config).isEmpty()) {
            return Look.refused("Node " + n.id() + ": " + n.source() + "'s settings are no longer accepted; open the "
                + "source and save it again to see why");
        }
        String sql = SqlTokens.checkCustomSql(n.until().query(), FlowDefinition.MAX_STATEMENT);
        try (Connection conn = jdbc.connect(config)) {
            conn.setAutoCommit(false);
            conn.setReadOnly(true);
            if (conn.unwrap(org.postgresql.PGConnection.class).getPreferQueryMode()
                == org.postgresql.jdbc.PreferQueryMode.SIMPLE) {
                return Look.refused("Node " + n.id() + ": " + n.source() + " sends SQL over the simple protocol "
                    + "(preferQueryMode=simple), which a query never uses. Remove that setting from the source");
            }
            String login = RuleRunner.privileged(conn);
            if (login != null) {
                return Look.refused("Node " + n.id() + ": " + SqlAction.forNode(login));
            }
            try {
                List<String> setup = new ArrayList<>(jdbc.dialect().readOnly());
                setup.addAll(jdbc.dialect().timeout(QUERY_TIMEOUT.toMillis()));
                for (String s : setup) {
                    try (Statement st = conn.createStatement()) {
                        st.execute(s);
                    }
                }
                PostgresConnector.conformingStrings(conn);
                PostgresConnector.requireConformingStrings(conn);
                // The query means what its author meant: the login's own search path, for this transaction
                io.kilnmark.kiln.plugins.jdbc.PostgresSession.asWritten(conn);
                try (java.sql.PreparedStatement st = conn.prepareStatement(sql)) {
                    st.setQueryTimeout((int) QUERY_TIMEOUT.toSeconds());
                    st.setMaxRows(1);
                    try (ResultSet rs = st.executeQuery()) {
                        boolean ready = rs.next() && rs.getMetaData().getColumnCount() >= 1 && rs.getBoolean(1)
                            && !rs.wasNull();
                        return Look.found(ready ? 1 : 0);
                    }
                }
            } finally {
                conn.rollback(); // nothing a look does is ever kept
            }
        } catch (SQLException e) {
            // Not the message: a driver can quote the URL, the query or a value. The state is enough to look further.
            LOG.debugf("wait-for %s: its query on %s failed (SQLSTATE %s)", n.id(), n.source(), e.getSQLState());
            return Look.problem("The query on " + n.source() + " could not run (SQLSTATE " + e.getSQLState() + ")");
        }
    }
}

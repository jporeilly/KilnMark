package io.kilnmark.kiln.plugins.jdbc;

import java.nio.charset.StandardCharsets;
import java.sql.BatchUpdateException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.arrow.vector.VectorSchemaRoot;

import io.kilnmark.kiln.Step;
import io.kilnmark.kiln.StepContext;
import io.kilnmark.kiln.StepException;
import io.kilnmark.kiln.connect.ConnectorException;
import io.kilnmark.kiln.connect.SourceConfig;
import io.kilnmark.kiln.plugins.jdbc.JdbcColumn.Kind;
import io.kilnmark.kiln.plugins.jdbc.WritePlan.Binding;
import io.kilnmark.kiln.plugins.jdbc.WritePlan.TargetColumn;
import io.kilnmark.kiln.plugins.write.WriteColumn;
import io.kilnmark.kiln.plugins.write.WriteReport;
import io.kilnmark.kiln.plugins.write.WriteSettings;

/**
 * Writes the batches arriving on its {@code in} port to a database table, under the source's own
 * login, in one transaction (or one per {@code commit_every} rows). Modes:
 *
 * <ul>
 *   <li>{@code create}: creates the table from the input's columns ({@link WriteSql#columnType})
 *       and fills it; fails if the table exists.</li>
 *   <li>{@code append}: adds rows to an existing table, after checking every input column fits
 *       ({@link WritePlan}).</li>
 *   <li>{@code replace}: creates and fills a new table under a temporary name, then in the same
 *       transaction renames the old table away, gives the new one its name and drops the old one,
 *       so readers see the old rows until the commit and the new rows after it. The old table's
 *       grants are repeated on the new one unless {@code keep_grants} is false; its indexes,
 *       constraints, triggers, comments and owner are not kept. Views or foreign keys that depend
 *       on the old table stop the replace before anything is written.</li>
 *   <li>{@code upsert}: inserts each row or updates the row with the same {@code key}
 *       (PostgreSQL {@code INSERT … ON CONFLICT}, which needs a unique index on exactly the key
 *       columns; SQL Server {@code MERGE … WITH (HOLDLOCK)}). Rows with an empty key column are
 *       refused; when the input repeats a key, its last row wins.</li>
 * </ul>
 *
 * <p>Rows go to PostgreSQL by {@code COPY} for create, append and replace, and as batches of
 * prepared statements for upsert; to SQL Server as batches of prepared statements, through the
 * driver's bulk copy for tables this step creates. Nothing is reported until the commit; see
 * {@link WriteReport}.
 */
final class JdbcTableOutput implements Step {

    static final String TYPE = "jdbc-table-output";

    /** The modes, as pipelines write them. */
    static final List<String> MODES = List.of("create", "append", "replace", "upsert");

    private final JdbcConnector connector;
    private final WriteSql sql;
    private final SourceConfig source;
    private final TableName target;
    private final String mode;
    private final List<String> key;
    private final WriteSettings settings;
    private final String assetUrn;
    private final long commitEvery;
    private final int batchSize;
    private final int textLength;
    private final boolean keepGrants;
    private final boolean bulk;

    private Connection connection;
    private WriteReport report;
    private boolean exists;
    private List<TargetColumn> targetColumns = List.of();
    private Set<String> required = Set.of();
    private List<String> grants = List.of();
    private TableName loadInto;
    private String newName;
    private List<WriteColumn> input;
    private List<Binding> bindings;
    private List<String> writtenTypes;
    private Loader loader;
    private long sinceCommit;
    private long committedRows;
    private boolean committed;

    JdbcTableOutput(JdbcConnector connector, WriteSql sql, SourceConfig source, TableName target, String mode,
                    List<String> key, WriteSettings settings, String assetUrn) throws ConnectorException {
        this.connector = connector;
        this.sql = sql;
        this.source = source;
        this.target = target;
        this.mode = mode;
        this.key = key;
        this.settings = settings;
        this.assetUrn = assetUrn;
        this.commitEvery = settings.number("commit_every", 0, 0, Long.MAX_VALUE);
        this.batchSize = (int) settings.number("batch_size", sql == WriteSql.SQL_SERVER && !mode.equals("upsert")
            ? 5000 : 1000, 1, 100_000);
        this.textLength = (int) settings.number("text_length", 0, 0, sql == WriteSql.SQL_SERVER ? 4000 : 10_485_760);
        this.keepGrants = settings.bool("keep_grants", true);
        this.bulk = sql == WriteSql.SQL_SERVER && (mode.equals("create") || mode.equals("replace"))
            && settings.bool("bulk_copy", true);
    }

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public void init(StepContext context) throws StepException {
        try {
            report = new WriteReport(context.stepName(), assetUrn, settings);
        } catch (ConnectorException e) {
            throw new StepException("Step " + context.stepName() + ": " + e.getMessage(), e);
        }
        try {
            connection = connector.connectForWrite(source, bulk);
            connection.setAutoCommit(false);
            exists = queryBoolean(sql.exists(target));
            if (mode.equals("create") && exists) {
                throw new StepException(target + " already exists in " + source.name() + ": use mode append to add "
                    + "rows to it, replace to replace it, or upsert to update rows by key");
            }
            if ((mode.equals("append") || mode.equals("upsert")) && !exists) {
                throw new StepException(target + " does not exist in " + source.name() + ": create it first with "
                    + "mode create (or replace), or check the schema and table names (they are case-sensitive)");
            }
            checkPrivileges();
            if (mode.equals("append") || mode.equals("upsert")) {
                targetColumns = readTargetColumns();
                required = readNames(sql.requiredColumns(target));
                if (mode.equals("upsert")) {
                    checkKey();
                }
            }
            if (mode.equals("replace") && exists) {
                List<String> blocking = new ArrayList<>(readNames(sql.dependents(target)));
                if (!blocking.isEmpty()) {
                    java.util.Collections.sort(blocking);
                    throw new StepException("Cannot replace " + target + ": " + String.join(", ", blocking)
                        + (blocking.size() == 1 ? " depends" : " depend") + " on it, and would stop the old table "
                        + "being dropped. Drop or change them first, or empty the table yourself and use mode append");
                }
                if (keepGrants) {
                    grants = readGrants();
                }
            }
        } catch (SQLException e) {
            throw new StepException(explain(e, 0), e);
        }
    }

    private void checkPrivileges() throws SQLException, StepException {
        for (WriteSql.Check check : sql.privileges(mode, target, exists)) {
            Boolean allowed = queryNullableBoolean(check.query());
            if (allowed == null) {
                if (check.missing() != null) {
                    throw new StepException("Cannot write to " + target + ": " + check.missing());
                }
            } else if (!allowed) {
                throw new StepException(denied(null));
            }
        }
    }

    /** The plain message for a refused write: who, where, and what to ask for. */
    private String denied(SQLException e) {
        String login = source.setting("username") == null ? "this source's login" : "the login "
            + source.setting("username");
        return login + " cannot write to " + target + " in " + source.name() + ": ask a database administrator to "
            + "grant " + sql.needs(mode, target, exists) + (e == null ? "" : " (" + e.getMessage() + ")");
    }

    private void checkKey() throws SQLException, StepException {
        List<String> missing = new ArrayList<>();
        List<String> keyColumns = new ArrayList<>();
        for (String k : key) {
            TargetColumn t = targetColumns.stream().filter(c -> c.name().equals(k)).findFirst()
                .orElseGet(() -> targetColumns.stream().filter(c -> sql.sameName(c.name(), k)).findFirst().orElse(null));
            if (t == null) {
                missing.add(k);
            } else {
                keyColumns.add(t.name());
            }
        }
        if (!missing.isEmpty()) {
            throw new StepException("Cannot upsert into " + target + ": it has no column " + String.join(", ", missing)
                + " to use as the key");
        }
        WriteSql.Sql unique = sql.uniqueKeys(target);
        if (unique == null) {
            return;
        }
        Map<Long, Set<String>> indexes = new HashMap<>();
        try (PreparedStatement ps = prepare(unique); ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                indexes.computeIfAbsent(rs.getLong(1), i -> new HashSet<>()).add(rs.getString(2));
            }
        }
        if (!indexes.containsValue(new HashSet<>(keyColumns))) {
            throw new StepException("Cannot upsert into " + target + " by (" + String.join(", ", keyColumns)
                + "): PostgreSQL finds the existing row through a unique index or primary key on exactly those "
                + "columns, and the table has none. Ask a database administrator to add one, for example CREATE "
                + "UNIQUE INDEX ON " + sql.table(target) + " (" + sql.columnList(keyColumns) + ")");
        }
    }

    private List<TargetColumn> readTargetColumns() throws SQLException {
        List<TargetColumn> out = new ArrayList<>();
        try (Statement s = connection.createStatement(); ResultSet rs = s.executeQuery(sql.probe(target))) {
            ResultSetMetaData md = rs.getMetaData();
            for (int i = 1; i <= md.getColumnCount(); i++) {
                JdbcColumn c = JdbcColumn.of(md, i);
                int type = md.getColumnType(i);
                int precision = md.getPrecision(i);
                boolean text = type == Types.CHAR || type == Types.VARCHAR || type == Types.NCHAR
                    || type == Types.NVARCHAR || type == Types.LONGVARCHAR || type == Types.LONGNVARCHAR;
                int maxLength = text && c.kind() == Kind.TEXT && precision > 0 && precision < 1_000_000 ? precision : 0;
                out.add(new TargetColumn(md.getColumnName(i), md.getColumnTypeName(i), c.kind(), precision,
                    md.getScale(i), maxLength));
            }
        }
        return out;
    }

    private Set<String> readNames(WriteSql.Sql query) throws SQLException {
        Set<String> out = new java.util.LinkedHashSet<>();
        try (PreparedStatement ps = prepare(query); ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                out.add(rs.getString(1));
            }
        }
        return out;
    }

    private List<String> readGrants() throws SQLException {
        // the statements are built for the new table's name, which is chosen now
        newName = "kiln_new_" + WriteSql.hexName();
        TableName fresh = new TableName(target.schema(), newName);
        List<String> out = new ArrayList<>();
        try (PreparedStatement ps = prepare(sql.grants(target)); ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                String g = sql.grant(fresh, rs);
                if (g != null) {
                    out.add(g);
                }
            }
        }
        return out;
    }

    @Override
    public boolean process(StepContext context) throws StepException {
        VectorSchemaRoot batch;
        try {
            batch = context.input().take(context.allocator());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new StepException("Interrupted", e);
        }
        if (batch == null) {
            finish(context);
            return false;
        }
        try {
            if (bindings == null) {
                start(batch);
            }
            report.accept(batch);
            loader.write(batch, report.rows() - batch.getRowCount());
            sinceCommit += batch.getRowCount();
            if (commitEvery > 0 && sinceCommit >= commitEvery) {
                loader.flush();
                connection.commit();
                committedRows += sinceCommit;
                sinceCommit = 0;
            }
        } catch (SQLException e) {
            throw new StepException(explain(e, report.rows()), e);
        } catch (StepException e) {
            throw committedRows > 0 ? new StepException(e.getMessage() + committedNote(), e) : e;
        } finally {
            batch.close();
        }
        return true;
    }

    /** On the first batch: creates the table (create, replace) or matches the input to it (append, upsert). */
    private void start(VectorSchemaRoot batch) throws SQLException, StepException {
        input = WriteColumn.of(batch.getSchema());
        switch (mode) {
            case "create", "replace" -> {
                checkNames();
                if (mode.equals("replace") && exists) {
                    if (newName == null) {
                        newName = "kiln_new_" + WriteSql.hexName();
                    }
                    loadInto = new TableName(target.schema(), newName);
                } else {
                    loadInto = target;
                }
                execute(new WriteSql.Sql(sql.createTable(loadInto, input, textLength)));
                for (String g : grants) {
                    execute(new WriteSql.Sql(g));
                }
                bindings = WritePlan.identity(input, textLength);
                writtenTypes = input.stream().map(c -> sql.columnType(c, textLength)).toList();
            }
            default -> {
                WritePlan.Plan plan = WritePlan.plan(input, targetColumns, required, key);
                if (!plan.problems().isEmpty()) {
                    throw new StepException(WritePlan.message(mode, target, plan.problems()));
                }
                loadInto = target;
                bindings = plan.bindings();
                Map<String, String> types = new HashMap<>();
                targetColumns.forEach(t -> types.put(t.name(), t.described()));
                writtenTypes = bindings.stream().map(b -> types.get(b.target())).toList();
            }
        }
        List<String> names = bindings.stream().map(Binding::target).toList();
        if (mode.equals("upsert")) {
            List<String> keyNames = bindings.stream().filter(Binding::key).map(Binding::target).toList();
            loader = new BatchLoader(sql.upsert(loadInto, names, keyNames));
        } else if (sql instanceof WriteSql.Postgres pg) {
            loader = new CopyLoader(pg.copy(loadInto, names));
        } else {
            loader = new BatchLoader(sql.insert(loadInto, names));
        }
    }

    /** Column names become identifiers: each must be there, fit, and appear once. */
    private void checkNames() throws StepException {
        List<String> problems = new ArrayList<>();
        String tooLong = sql.identifierProblem(loadIntoName());
        if (tooLong != null) {
            problems.add(tooLong);
        }
        for (int i = 0; i < input.size(); i++) {
            String n = input.get(i).name();
            if (n == null || n.isBlank()) {
                problems.add("column " + (i + 1) + " has no name");
                continue;
            }
            String p = sql.identifierProblem(n);
            if (p != null) {
                problems.add(p);
            }
            for (int j = 0; j < i; j++) {
                if (sql.sameName(n, input.get(j).name())) {
                    problems.add("the column name " + n + " appears twice");
                }
            }
        }
        if (!problems.isEmpty()) {
            throw new StepException("Cannot create " + target + ": " + String.join("; ", problems)
                + ". Rename columns with a select step first");
        }
    }

    private String loadIntoName() {
        return target.table();
    }

    private void finish(StepContext context) throws StepException {
        try {
            if (bindings == null) {
                if (mode.equals("create") || mode.equals("replace")) {
                    throw new StepException("Nothing arrived to write to " + target
                        + ": the step before sent no columns, so there is no table to create");
                }
                connection.commit();
                committed = true;
                return;
            }
            loader.flush();
            if (newName != null && loadInto != null && !loadInto.equals(target)) {
                for (WriteSql.Sql s : sql.swap(target, newName, "kiln_old_" + WriteSql.hexName())) {
                    execute(s);
                }
            }
            connection.commit();
            committed = true;
        } catch (SQLException e) {
            throw new StepException(explain(e, report.rows()), e);
        }
        Map<String, String> details = new LinkedHashMap<>();
        details.put("target", target.toString());
        details.put("mode", mode);
        List<WriteColumn> written = bindings.stream()
            .map(b -> new WriteColumn(b.target(), b.input().kind(), b.input().precision(), b.input().scale(),
                b.input().arrowType())).toList();
        report.emit(context.metadata(), written, writtenTypes, details);
    }

    @Override
    public void close() {
        if (connection == null) {
            return;
        }
        if (!committed) {
            if (loader != null) {
                loader.abort();
            }
            try {
                connection.rollback();
            } catch (SQLException | RuntimeException e) {
                // the connection is closed below; the server rolls back an unfinished transaction
            }
            if (committedRows > 0 && newName != null && loadInto != null && !loadInto.equals(target)) {
                // replace with commit_every: the half-filled new table was committed in parts
                try (Statement s = connection.createStatement()) {
                    s.execute(sql.drop(loadInto));
                    connection.commit();
                } catch (SQLException | RuntimeException e) {
                    // left for an administrator; its name starts kiln_new_
                }
            }
        }
        try {
            connection.close();
        } catch (SQLException | RuntimeException e) {
            // nothing more to release
        }
    }

    /** A failure as a person should read it, with what happened to rows already committed. */
    private String explain(SQLException e, long rows) {
        SQLException cause = e instanceof BatchUpdateException b && b.getNextException() != null ? b.getNextException() : e;
        String message = sql.permissionDenied(cause) ? denied(cause)
            : "Writing to " + target + " failed" + (rows > 0 ? " after " + rows + " rows" : "") + ": "
            + connector.explain(cause);
        return message + committedNote();
    }

    /** With {@code commit_every}, what happened to the rows already committed; empty otherwise. */
    private String committedNote() {
        if (committedRows == 0) {
            return "";
        }
        return newName != null && loadInto != null && !loadInto.equals(target)
            ? ". " + target + " is unchanged; the partly filled new table is dropped"
            : ". " + committedRows + " rows were already committed, because commit_every is set; they stay in "
                + target;
    }

    private PreparedStatement prepare(WriteSql.Sql query) throws SQLException {
        PreparedStatement ps = connection.prepareStatement(query.text());
        for (int i = 0; i < query.params().size(); i++) {
            ps.setString(i + 1, query.params().get(i));
        }
        return ps;
    }

    private void execute(WriteSql.Sql statement) throws SQLException {
        try (PreparedStatement ps = prepare(statement)) {
            ps.execute();
        }
    }

    private boolean queryBoolean(WriteSql.Sql query) throws SQLException {
        Boolean b = queryNullableBoolean(query);
        return b != null && b;
    }

    private Boolean queryNullableBoolean(WriteSql.Sql query) throws SQLException {
        try (PreparedStatement ps = prepare(query); ResultSet rs = ps.executeQuery()) {
            if (!rs.next()) {
                return null;
            }
            boolean b = rs.getBoolean(1);
            return rs.wasNull() ? null : b;
        }
    }

    /** The value of {@code binding} in {@code row}, converted, with the checks a database would fail on less plainly. */
    private Object value(Binding binding, VectorSchemaRoot batch, int row, long rowNumber) throws StepException {
        Object v = WritePlan.convert(binding.input().value(batch.getVector(binding.index()), row),
            binding.input().kind(), binding.kind());
        if (v == null) {
            if (binding.key()) {
                throw new StepException("Row " + rowNumber + " has no value in the key column " + binding.target()
                    + "; upsert needs every key column filled");
            }
            return null;
        }
        if (v instanceof String s) {
            String problem = sql.textProblem(s);
            if (problem != null) {
                throw new StepException("Row " + rowNumber + ", column " + binding.target() + ": " + problem);
            }
            if (binding.maxLength() > 0 && sql.length(s) > binding.maxLength()) {
                throw new StepException("Row " + rowNumber + ": the value in column " + binding.target() + " is "
                    + sql.length(s) + " characters long, but the column holds at most " + binding.maxLength()
                    + ". Shorten the values with a compute step, or ask for the column to be widened");
            }
        }
        return v;
    }

    // ------------------------------------------------------------------ loaders

    /** Sends rows to the table. */
    private interface Loader {

        /** Sends a batch; {@code before} rows were sent before it. */
        void write(VectorSchemaRoot batch, long before) throws SQLException, StepException;

        /** Sends what is buffered, so a commit includes it. */
        void flush() throws SQLException;

        /** Stops sending after a failure. Never fails. */
        void abort();
    }

    /** PostgreSQL's {@code COPY … FROM STDIN}, as CSV, one {@code COPY} per transaction. */
    private final class CopyLoader implements Loader {

        private final String copySql;
        private org.postgresql.copy.CopyIn copy;

        CopyLoader(String copySql) {
            this.copySql = copySql;
        }

        @Override
        public void write(VectorSchemaRoot batch, long before) throws SQLException, StepException {
            int n = batch.getRowCount();
            if (n == 0) {
                return;
            }
            StringBuilder b = new StringBuilder(n * 32);
            for (int row = 0; row < n; row++) {
                for (int c = 0; c < bindings.size(); c++) {
                    if (c > 0) {
                        b.append(',');
                    }
                    Binding binding = bindings.get(c);
                    WriteSql.Postgres.copyValue(b, binding.kind(), value(binding, batch, row, before + row + 1));
                }
                b.append('\n');
            }
            if (copy == null) {
                copy = connection.unwrap(org.postgresql.PGConnection.class).getCopyAPI().copyIn(copySql);
            }
            byte[] bytes = b.toString().getBytes(StandardCharsets.UTF_8);
            copy.writeToCopy(bytes, 0, bytes.length);
        }

        @Override
        public void flush() throws SQLException {
            if (copy != null) {
                org.postgresql.copy.CopyIn c = copy;
                copy = null;
                c.endCopy();
            }
        }

        @Override
        public void abort() {
            if (copy != null && copy.isActive()) {
                try {
                    copy.cancelCopy();
                } catch (SQLException | RuntimeException e) {
                    // rolled back with the transaction
                }
            }
            copy = null;
        }
    }

    /** Batches of one prepared statement, a row each: inserts, upserts and SQL Server's bulk copy. */
    private final class BatchLoader implements Loader {

        private final String statement;
        private PreparedStatement ps;
        private int pending;

        BatchLoader(String statement) {
            this.statement = statement;
        }

        @Override
        public void write(VectorSchemaRoot batch, long before) throws SQLException, StepException {
            if (ps == null) {
                ps = connection.prepareStatement(statement);
            }
            int n = batch.getRowCount();
            for (int row = 0; row < n; row++) {
                for (int c = 0; c < bindings.size(); c++) {
                    Binding binding = bindings.get(c);
                    sql.bind(ps, c + 1, binding.kind(), value(binding, batch, row, before + row + 1));
                }
                ps.addBatch();
                if (++pending >= batchSize) {
                    ps.executeBatch();
                    pending = 0;
                }
            }
        }

        @Override
        public void flush() throws SQLException {
            if (ps != null && pending > 0) {
                ps.executeBatch();
                pending = 0;
            }
        }

        @Override
        public void abort() {
            if (ps != null) {
                try {
                    ps.close();
                } catch (SQLException | RuntimeException e) {
                    // rolled back with the transaction
                }
            }
        }
    }
}

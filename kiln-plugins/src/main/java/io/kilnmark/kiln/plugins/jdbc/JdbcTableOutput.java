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

import io.kilnmark.kiln.Node;
import io.kilnmark.kiln.NodeContext;
import io.kilnmark.kiln.NodeException;
import io.kilnmark.kiln.RowErrors;
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
 * driver's bulk copy for tables this node creates. Nothing is reported until the commit; see
 * {@link WriteReport}.
 *
 * <p>Each value is converted and checked before it is sent ({@link #value}). A row with a value the
 * table would refuse goes to the {@code errors} port when something is connected to it (ADR 0054),
 * with a fixed wording that never holds the value, and is never sent; otherwise it stops the write,
 * as before. A refusal by the database itself (a unique index, a foreign key, a check) still stops
 * the write: it is found only when a batch is sent.
 */
final class JdbcTableOutput implements Node {

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
    private final Map<String, String> profiling;
    private final long commitEvery;
    private final int batchSize;
    private final int textLength;
    private final boolean keepGrants;
    private final boolean bulk;
    private final boolean allowDroppingRowSecurity;
    private final boolean allowOtherUniqueKeys;
    private final long lockTimeoutMillis;

    private Connection connection;
    private WriteReport report;
    private boolean exists;
    private List<TargetColumn> targetColumns = List.of();
    private Set<String> required = Set.of();
    private List<String> grants = List.of();
    private TableName loadInto;
    /** For replace: the name the new table is filled under, swapped in at the end. */
    private final String newName;
    private boolean copyRefused;
    private String oldOwner;
    private boolean ownerSettable;
    private List<WriteColumn> input;
    private List<Binding> bindings;
    private List<String> writtenTypes;
    private Loader loader;
    private long sinceCommit;
    /** Rows that have arrived, those set aside included, for the row numbers in messages. */
    private long arrived;
    /** Rows that arrived and rows sent to the errors port, for the run's log. */
    private final RowErrors.Tally setAside = new RowErrors.Tally(null, null);
    private long committedRows;
    private boolean committed;
    /** Whether this node created {@link #loadInto}. */
    private boolean created;
    /** Timestamp and time columns of the table that keep fewer than nine digits of a second, by name. */
    private final Map<String, Integer> fractionDigits = new HashMap<>();
    /** For append and upsert: the table's columns by name. */
    private final Map<String, TargetColumn> targets = new HashMap<>();

    JdbcTableOutput(JdbcConnector connector, WriteSql sql, SourceConfig source, TableName target, String mode,
                    List<String> key, WriteSettings settings, String assetUrn, Map<String, String> profiling)
        throws ConnectorException {
        this.profiling = profiling;
        this.connector = connector;
        this.sql = sql;
        this.source = source;
        this.target = target;
        this.mode = mode;
        this.key = key;
        this.settings = settings;
        this.assetUrn = assetUrn;
        this.commitEvery = settings.number("commit_every", 0, 0, Long.MAX_VALUE);
        this.batchSize = (int) settings.number("batch_size", sql.defaultBatchSize(mode), 1, 100_000);
        this.textLength = (int) settings.number("text_length", 0, 0, sql.maxTextLength());
        this.keepGrants = settings.bool("keep_grants", true);
        this.bulk = sql.bulk(mode, settings);
        this.allowDroppingRowSecurity = settings.bool("allow_dropping_row_security", false);
        this.allowOtherUniqueKeys = settings.bool("allow_other_unique_keys", false);
        this.lockTimeoutMillis = settings.number("lock_timeout", 30, 1, 3600) * 1000;
        // where DDL commits by itself, a created table is also filled under a temporary name, so the
        // target never appears half-filled
        this.newName = mode.equals("replace") || (mode.equals("create") && !sql.transactionalDdl())
            ? "kiln_new_" + WriteSql.hexName() : null;
    }

    /** Whether the write leaves the table holding exactly what was written (create, replace). */
    private boolean wholeTable() {
        return mode.equals("create") || mode.equals("replace");
    }

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public void init(NodeContext context) throws NodeException {
        try {
            report = new WriteReport(context.nodeName(), assetUrn, settings, profiling, wholeTable());
        } catch (ConnectorException e) {
            throw new NodeException("Node " + context.nodeName() + ": " + e.getMessage(), e);
        }
        try {
            connection = connector.connectForWrite(source, bulk);
            sql.prepareSession(connection);
            connection.setAutoCommit(false);
            if (connection.getAutoCommit() || !connection.getMetaData().supportsTransactions()) {
                // every row would be committed as it is sent: a failure would leave part of the write
                throw new NodeException("Cannot write to " + source.name() + ": its driver does not offer "
                    + "transactions, so a failed write could not be undone and part of it would stay");
            }
            Boolean known = sql.existsByMetadata(connection, target);
            exists = known != null ? known : queryBoolean(sql.exists(target));
            if (mode.equals("create") && exists) {
                throw new NodeException(target + " already exists in " + source.name() + ": use mode append to add "
                    + "rows to it, replace to replace it, or upsert to update rows by key");
            }
            if ((mode.equals("append") || mode.equals("upsert")) && !exists) {
                throw new NodeException(target + " does not exist in " + source.name() + ": create it first with "
                    + "mode create (or replace), or check the schema and table names (they are case-sensitive)");
            }
            checkPrivileges();
            if (mode.equals("append") || mode.equals("upsert")) {
                targetColumns = connector.targetColumns(connection, source, target);
                if (targetColumns == null) {
                    targetColumns = sql.targetColumnsByMetadata(connection, target);
                }
                if (targetColumns == null) {
                    targetColumns = readTargetColumns();
                }
                Set<String> requiredKnown = sql.requiredByMetadata(connection, target);
                required = requiredKnown != null ? requiredKnown : readNames(sql.requiredColumns(target));
                if (mode.equals("upsert")) {
                    checkKey();
                }
                WriteSql.Sql refused = sql.copyRefused(target);
                copyRefused = refused != null && queryBoolean(refused);
            }
            if (mode.equals("replace") && exists) {
                checkReplaceable();
                if (keepGrants) {
                    grants = readGrants();
                }
                try (PreparedStatement ps = prepare(sql.owner(target)); ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        oldOwner = rs.getString(1);
                        ownerSettable = rs.getBoolean(2);
                    }
                }
            }
        } catch (SQLException e) {
            throw new NodeException(explain(e, 0), e);
        }
    }

    /**
     * Replace drops the old table: refuse, before anything is written, when that would break
     * something or silently change who may see what.
     */
    private void checkReplaceable() throws SQLException, NodeException {
        Set<String> found = new java.util.LinkedHashSet<>(readNames(sql.blockers(target)));
        WriteSql.Sql hidden = sql.hiddenBlockers(target);
        if (hidden != null) {
            try {
                found.addAll(readNames(hidden));
            } catch (SQLException e) {
                // not readable by this login (MySQL's InnoDB views need PROCESS); the swap still puts the
                // old table back if a foreign key it could not see refuses the drop
            }
        }
        List<String> blocking = new ArrayList<>(found);
        if (!blocking.isEmpty()) {
            java.util.Collections.sort(blocking);
            throw new NodeException("Cannot replace " + target + ": " + String.join("; ", blocking) + ". Replace "
                + "drops the old table and puts a new one in its place, which would break that or be refused. Change "
                + "or drop it first, or empty the table yourself and use mode append");
        }
        if (queryBoolean(sql.rowSecurity(target)) && !allowDroppingRowSecurity) {
            throw new NodeException("Cannot replace " + target + ": it has row-level security, which the new table "
                + "would not have, while its grants would be repeated, so rows the policies hide would become visible "
                + "to those grants. Set allow_dropping_row_security: true if that is intended (and add the policies "
                + "again afterwards), or empty the table yourself and use mode append");
        }
    }

    private void checkPrivileges() throws SQLException, NodeException {
        for (WriteSql.Check check : sql.privileges(mode, target, exists)) {
            Boolean allowed = queryNullableBoolean(check.query());
            if (allowed == null) {
                if (check.missing() != null) {
                    throw new NodeException("Cannot write to " + target + ": " + check.missing());
                }
            } else if (!allowed) {
                throw new NodeException(denied(null));
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

    private void checkKey() throws SQLException, NodeException {
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
            throw new NodeException("Cannot upsert into " + target + ": it has no column " + String.join(", ", missing)
                + " to use as the key");
        }
        WriteSql.Sql unique = sql.uniqueKeys(target);
        if (unique == null) {
            return;
        }
        Map<String, Set<String>> indexes = new HashMap<>();
        try (PreparedStatement ps = prepare(unique); ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                indexes.computeIfAbsent(rs.getString(1), i -> new HashSet<>()).add(rs.getString(2));
            }
        }
        if (!indexes.containsValue(new HashSet<>(keyColumns))) {
            throw new NodeException("Cannot upsert into " + target + " by (" + String.join(", ", keyColumns)
                + "): " + sql.keyIndexAdvice(target, keyColumns));
        }
        checkOtherUniqueKeys(keyColumns);
    }

    /**
     * Where a conflict with any unique index turns the insert into an update (MySQL), a unique index
     * without every key column could match another row than the key's, which would be overwritten:
     * refused, naming the index, unless {@code allow_other_unique_keys} is true.
     */
    private void checkOtherUniqueKeys(List<String> keyColumns) throws SQLException, NodeException {
        WriteSql.Sql all = sql.allUniqueKeys(target);
        if (all == null || allowOtherUniqueKeys) {
            return;
        }
        Map<String, List<String>> indexes = new LinkedHashMap<>();
        try (PreparedStatement ps = prepare(all); ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                indexes.computeIfAbsent(rs.getString(1), i -> new ArrayList<>()).add(rs.getString(2));
            }
        }
        List<String> others = new ArrayList<>();
        indexes.forEach((index, columns) -> {
            boolean coversKey = keyColumns.stream().allMatch(k -> columns.stream()
                .anyMatch(c -> c != null && sql.sameName(c, k)));
            if (!coversKey) {
                others.add(index);
            }
        });
        if (!others.isEmpty()) {
            throw new NodeException("Cannot upsert into " + target + " by (" + String.join(", ", keyColumns) + "): the "
                + "unique " + (others.size() == 1 ? "index " : "indexes ") + String.join(", ", others) + " "
                + (others.size() == 1 ? "does" : "do") + " not hold every key column, and a row matching "
                + (others.size() == 1 ? "it" : "one of them") + " would be updated instead of inserted, even when its "
                + "key is another row's. Set allow_other_unique_keys: true if updating such rows is intended, or "
                + "upsert into a table whose other unique indexes include the key columns");
        }
    }

    /**
     * The table's columns, from a prepared statement's description without running it, so a login
     * that may only insert (or select some columns) can still append.
     */
    private List<TargetColumn> readTargetColumns() throws SQLException {
        List<TargetColumn> out = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement(sql.probe(target))) {
            ResultSetMetaData md = ps.getMetaData();
            if (md == null) {
                throw new SQLException("The database did not describe the columns of " + target);
            }
            for (int i = 1; i <= md.getColumnCount(); i++) {
                JdbcColumn c = JdbcColumn.of(md, i);
                int type = md.getColumnType(i);
                int precision = md.getPrecision(i);
                boolean text = type == Types.CHAR || type == Types.VARCHAR || type == Types.NCHAR
                    || type == Types.NVARCHAR || type == Types.LONGVARCHAR || type == Types.LONGNVARCHAR;
                int maxLength = text && c.kind() == Kind.TEXT && precision > 0 && precision < 1_000_000 ? precision : 0;
                out.add(new TargetColumn(md.getColumnName(i), md.getColumnTypeName(i), c.kind(), precision,
                    scale(md.getScale(i)), maxLength));
            }
        }
        return out;
    }

    /**
     * A column's scale as the driver reports it, with PostgreSQL 15's negative scales decoded: the
     * type modifier keeps the scale in 11 bits, which the driver hands over unsigned
     * ({@code numeric(5,-2)} arrives as 2046).
     */
    static int scale(int reported) {
        return reported > 1000 && reported < 2048 ? reported - 2048 : reported;
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
    public boolean process(NodeContext context) throws NodeException {
        VectorSchemaRoot batch;
        try {
            batch = context.input().take(context.allocator());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new NodeException("Interrupted", e);
        }
        if (batch == null) {
            finish(context);
            return false;
        }
        try {
            if (bindings == null) {
                start(batch);
            }
            int n = batch.getRowCount();
            boolean aside = context.outputPorts().contains(RowErrors.PORT);
            Object[][] values = new Object[n][];
            int[] good = new int[n];
            int[] bad = new int[n];
            RowErrors.Failure[] why = new RowErrors.Failure[n];
            int g = 0;
            int b = 0;
            for (int row = 0; row < n; row++) {
                try {
                    values[g] = rowValues(batch, row, arrived + row + 1);
                    good[g++] = row;
                } catch (Refused r) {
                    if (!aside) {
                        throw r;
                    }
                    why[b] = r.failure();
                    bad[b++] = row;
                }
            }
            arrived += n;
            setAside.arrived(n);
            if (b > 0) {
                setAside(context, batch, bad, why, b);
                setAside.failed(b);
                if (g > 0) {
                    try (VectorSchemaRoot kept = RowErrors.rows(batch, good, g, context.allocator())) {
                        report.accept(kept);
                    }
                }
            } else {
                report.accept(batch);
            }
            loader.write(g == n ? values : java.util.Arrays.copyOf(values, g));
            sinceCommit += g;
            if (commitEvery > 0 && sinceCommit >= commitEvery) {
                loader.flush();
                connection.commit();
                committedRows += sinceCommit;
                sinceCommit = 0;
            }
        } catch (SQLException e) {
            throw new NodeException(explain(e, report.rows()), e);
        } catch (NodeException e) {
            throw committedRows > 0 ? new NodeException(e.getMessage() + committedNote(), e) : e;
        } finally {
            batch.close();
        }
        return true;
    }

    /** Every bound value of one row, converted and checked; {@link Refused} names the first that fails. */
    private Object[] rowValues(VectorSchemaRoot batch, int row, long rowNumber) throws NodeException {
        Object[] out = new Object[bindings.size()];
        for (int c = 0; c < out.length; c++) {
            out[c] = value(bindings.get(c), batch, row, rowNumber);
        }
        return out;
    }

    /** Sends rows the table would refuse to the errors port. */
    private static void setAside(NodeContext context, VectorSchemaRoot batch, int[] rows, RowErrors.Failure[] why,
                                 int count) throws NodeException {
        VectorSchemaRoot errors = RowErrors.batch(batch, rows, why, count, context.nodeName(), context.allocator());
        try {
            context.emit(RowErrors.PORT, errors);
        } catch (InterruptedException e) {
            errors.close(); // interrupted while waiting for room: the batch was not handed over
            Thread.currentThread().interrupt();
            throw new NodeException("Interrupted", e);
        }
    }

    /**
     * A value the table would refuse, found before it is sent. Its message says which row (counting
     * every row that arrived) and column, for a run that stops; {@link #failure()} is the fixed wording
     * an error row carries, which never holds the value.
     */
    static final class Refused extends NodeException {
        @java.io.Serial
        private static final long serialVersionUID = 1L;
        private final String column;
        private final String code;
        private final String wording;

        Refused(String message, String column, String code, String wording) {
            super(message);
            this.column = column;
            this.code = code;
            this.wording = wording;
        }

        RowErrors.Failure failure() {
            return new RowErrors.Failure(column, code, wording);
        }
    }

    /** On the first batch: creates the table (create, replace) or matches the input to it (append, upsert). */
    private void start(VectorSchemaRoot batch) throws SQLException, NodeException {
        input = WriteColumn.of(batch.getSchema());
        switch (mode) {
            case "create", "replace" -> {
                checkNames();
                // replace always fills a new table and renames it at the end, so the target is never half-filled
                loadInto = newName != null ? new TableName(target.schema(), newName) : target;
                String cannot = sql.createProblem(input, textLength);
                if (cannot != null) {
                    throw new NodeException("Cannot create " + target + ": " + cannot);
                }
                execute(new WriteSql.Sql(sql.createTable(loadInto, input, textLength)));
                created = true;
                for (String g : grants) {
                    execute(new WriteSql.Sql(g));
                }
                bindings = WritePlan.identity(input, textLength).stream()
                    .map(b -> new Binding(b.index(), b.input(), sql.storedName(b.target()), b.kind(), b.maxLength(),
                        b.key(), b.precision(), b.scale())).toList();
                writtenTypes = input.stream().map(c -> sql.columnType(c, textLength)).toList();
                if (sql.checksCreatedTable()) {
                    checkAsCreated();
                }
            }
            default -> {
                WritePlan.Plan plan = WritePlan.plan(input, targetColumns, required, key);
                List<String> problems = new ArrayList<>(plan.problems());
                targetColumns.forEach(t -> targets.put(t.name(), t));
                for (Binding b : plan.bindings()) {
                    String misfit = sql.targetMisfit(b.input(), targets.get(b.target()));
                    if (misfit != null) {
                        problems.add(misfit);
                    }
                }
                if (!problems.isEmpty()) {
                    throw new NodeException(WritePlan.message(mode, target, problems));
                }
                loadInto = target;
                bindings = plan.bindings();
                Map<String, String> types = new HashMap<>();
                targetColumns.forEach(t -> types.put(t.name(), t.described()));
                targetColumns.forEach(t -> {
                    int digits = sql.fractionDigits(t);
                    if (digits >= 0 && digits < 9) {
                        fractionDigits.put(t.name(), digits);
                    }
                });
                writtenTypes = bindings.stream().map(b -> types.get(b.target())).toList();
            }
        }
        List<String> names = bindings.stream().map(Binding::target).toList();
        if (mode.equals("upsert")) {
            List<String> keyNames = bindings.stream().filter(Binding::key).map(Binding::target).toList();
            loader = new BatchLoader(sql.upsert(loadInto, names, keyNames));
        } else if (sql instanceof WriteSql.Postgres pg && !copyRefused) {
            loader = new CopyLoader(pg.copy(loadInto, names));
        } else if (bulk && sql instanceof MySqlWriteSql my && queryBoolean(my.localInfileAllowed())) {
            loader = new LoadDataLoader(my, names);
        } else {
            loader = new BatchLoader(sql.insert(loadInto, names));
        }
    }

    /**
     * The table just created, as the database describes it: each value is then checked against the
     * columns it actually made, as for an append ({@link WriteSql#checksCreatedTable()}).
     */
    private void checkAsCreated() throws SQLException, NodeException {
        List<TargetColumn> made = connector.targetColumns(connection, source, loadInto);
        if (made == null) {
            made = sql.targetColumnsByMetadata(connection, loadInto);
        }
        if (made == null) {
            made = readTargetColumns();
        }
        made.forEach(t -> targets.put(t.name(), t));
        List<Binding> checked = new ArrayList<>(bindings.size());
        for (Binding b : bindings) {
            TargetColumn t = targets.get(b.target());
            if (t == null) {
                throw new NodeException("Created " + target + ", but the database does not describe its column "
                    + b.target() + ", so its values cannot be checked before they are written");
            }
            String misfit = sql.targetMisfit(b.input(), t);
            if (misfit != null) {
                throw new NodeException("Cannot create " + target + ": " + misfit);
            }
            int digits = sql.fractionDigits(t);
            if (digits >= 0 && digits < 9) {
                fractionDigits.put(t.name(), digits);
            }
            boolean declared = t.kind() == Kind.DECIMAL && !t.unconstrainedDecimal();
            checked.add(new Binding(b.index(), b.input(), b.target(), b.kind(), t.maxLength(), b.key(),
                declared ? t.precision() : 0, declared ? t.scale() : 0));
        }
        bindings = checked;
        writtenTypes = bindings.stream().map(b -> targets.get(b.target()).described()).toList();
    }

    /** Column names become identifiers: each must be there, fit, and appear once. */
    private void checkNames() throws NodeException {
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
            throw new NodeException("Cannot create " + target + ": " + String.join("; ", problems)
                + ". Rename columns with a select node first");
        }
    }

    private String loadIntoName() {
        return target.table();
    }

    private void finish(NodeContext context) throws NodeException {
        String aside = setAside.note();
        if (aside != null) {
            context.note(aside);
        }
        try {
            if (bindings == null) {
                if (mode.equals("create") || mode.equals("replace")) {
                    throw new NodeException("Nothing arrived to write to " + target
                        + ": the node before sent no columns, so there is no table to create");
                }
                connection.commit();
                committed = true;
                return;
            }
            loader.flush();
            if (newName != null) {
                swapIn();
            }
            connection.commit();
            committed = true;
        } catch (SQLException e) {
            throw new NodeException(explain(e, report.rows()), e);
        }
        Map<String, String> details = new LinkedHashMap<>();
        details.put("target", target.toString());
        details.put("mode", mode);
        List<WriteColumn> written = bindings.stream()
            .map(b -> new WriteColumn(b.target(), b.input().kind(), b.input().precision(), b.input().scale(),
                b.input().arrowType())).toList();
        report.emit(context.metadata(), written, writtenTypes, details);
    }

    /**
     * Replace's last step: the old owner gets the new table (or, where the login may not hand it
     * over, the owner's rights on it), then the new table takes the target's name, waiting at most
     * {@code lock_timeout} for readers of the old table.
     */
    private void swapIn() throws SQLException, NodeException {
        for (String s : sql.lockTimeout(lockTimeoutMillis)) {
            execute(new WriteSql.Sql(s));
        }
        if (oldOwner != null) {
            execute(new WriteSql.Sql(ownerSettable ? sql.setOwner(loadInto, oldOwner)
                : sql.grantOwnerRights(loadInto, oldOwner)));
        }
        String oldName = "kiln_old_" + WriteSql.hexName();
        List<WriteSql.Sql> nodes = exists ? sql.swap(target, newName, oldName)
            : List.of(sql.rename(loadInto, target.table()));
        for (int i = 0; i < nodes.size(); i++) {
            if (exists && !sql.transactionalDdl() && nodes.size() > 1 && i == nodes.size() - 1) {
                // The new table is in place and committed; the old one goes last, by itself.
                try {
                    execute(nodes.get(i));
                } catch (SQLException e) {
                    oldTableKept(oldName, e);
                }
            } else {
                execute(nodes.get(i));
            }
        }
    }

    /**
     * Where DDL commits by itself, dropping the old table failed after the new one took its name.
     * A foreign key still referring to it (on a table the login cannot see, so the checks before
     * the write missed it) now points at the old table under its temporary name: the names are put
     * back, so the key refers to the table it did, and the replace fails, the new table dropped with
     * the run. Any other failure leaves the old table for an administrator, with a warning on the run.
     */
    private void oldTableKept(String oldName, SQLException e) throws SQLException, NodeException {
        WriteSql.Sql back = sql.swapBack(target, newName, oldName);
        if (sql.foreignKeyRefused(e) && back != null) {
            execute(back);
            throw new NodeException("Cannot replace " + target + ": a foreign key on another table refers to it, on a "
                + "table this login has no rights on, so it could not be seen before writing. Replace drops the old "
                + "table, which that key refuses, so " + target + " is unchanged and the new rows were not kept. Ask "
                + "a database administrator to drop or change that foreign key, or empty the table yourself and use "
                + "mode append (" + e.getMessage() + ")");
        }
        TableName old = new TableName(target.schema(), oldName);
        report.warn("The new rows are in " + target + ", but the old table could not be dropped and is kept as " + old
            + ": ask a database administrator to drop it");
    }

    @Override
    public void close() {
        if (loader != null) {
            loader.release();
        }
        if (connection == null) {
            return;
        }
        if (!committed) {
            try {
                connection.rollback();
            } catch (SQLException | RuntimeException e) {
                // the connection is closed below; the server rolls back an unfinished transaction
            }
            if (newName == null && created && committedRows == 0 && sql.dropCreatedOnFailure()) {
                // DDL that commits by itself: the table this node created outlived the rollback. Dropped
                // only while it is empty, so rows another writer put there meanwhile are never lost.
                try (Statement s = connection.createStatement()) {
                    boolean empty;
                    try (ResultSet rs = s.executeQuery("SELECT COUNT(*) FROM " + sql.table(loadInto))) {
                        empty = rs.next() && rs.getLong(1) == 0;
                    }
                    connection.commit();
                    if (empty) {
                        s.execute(sql.drop(loadInto));
                        connection.commit();
                    }
                } catch (SQLException | RuntimeException e) {
                    // left for an administrator: the write's error names the table
                }
            }
            if (newName != null && created && (committedRows > 0 || !sql.transactionalDdl())) {
                // replace with commit_every, or DDL that committed by itself: the new table outlived the rollback
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
        String message = sql.policyRefused(cause) ? "A row-level security policy on " + target + " refused a row"
            + (rows > 0 ? " after " + rows + " rows" : "") + " (" + cause.getMessage() + "); the rows the policy "
            + "allows were not written either, since the write is one transaction"
            : sql.lockTimedOut(cause) ? "Could not swap the new table in for " + target + " within "
            + lockTimeoutMillis / 1000 + " seconds: other sessions were using the old table. Try again when it is "
            + "quieter, or raise lock_timeout"
            : sql.permissionDenied(cause) ? denied(cause)
            : "Writing to " + target + " failed" + (rows > 0 ? " after " + rows + " rows" : "") + ": "
            + connector.explain(cause);
        return message + committedNote();
    }

    /** With {@code commit_every}, what happened to the rows already committed; empty otherwise. */
    private String committedNote() {
        if (committedRows == 0) {
            return "";
        }
        return newName != null
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
    // The nanoseconds within the second are wanted: the fraction a column may be too short for.
    @SuppressWarnings({"JavaLocalDateTimeGetNano", "JavaInstantGetSecondsGetNano", "JavaLocalTimeGetNano"})
    private Object value(Binding binding, VectorSchemaRoot batch, int row, long rowNumber) throws NodeException {
        Object v = WritePlan.convert(binding.input().value(batch.getVector(binding.index()), row),
            binding.input().kind(), binding.kind());
        String column = binding.input().name();
        if (v == null) {
            if (binding.key()) {
                throw new Refused("Row " + rowNumber + " has no value in the key column " + binding.target()
                    + "; upsert needs every key column filled", column, "key_missing",
                    "the key column is empty; upsert needs every key column filled");
            }
            return null;
        }
        if (binding.key() && sql.emptyMeansNull()
            && ((v instanceof String text && text.isEmpty()) || (v instanceof byte[] bytes && bytes.length == 0))) {
            // whatever empty_text_as_null says: as null, the key would match no row and add one each time
            throw new Refused("Row " + rowNumber + " has an empty value in the key column " + binding.target()
                + ", which the database stores as null, so it could never match a row; upsert needs every key "
                + "column filled", column, "key_empty", "the key column holds empty text, which the database stores "
                + "as null, so it could never match a row; upsert needs every key column filled");
        }
        String problem = sql.valueProblem(binding.kind(), v);
        if (problem != null) {
            throw new Refused("Row " + rowNumber + ", column " + binding.target() + ": " + problem, column,
                "not_storable", "the database cannot store this value in the column's type");
        }
        if (v instanceof java.math.BigDecimal d && binding.precision() > 0) {
            java.math.BigDecimal exact = WritePlan.fitDecimal(d, binding.precision(), binding.scale());
            if (exact == null) {
                String kept = "decimal(" + binding.precision() + "," + binding.scale() + ")";
                throw new Refused("Row " + rowNumber + ": the value in column " + binding.target() + " "
                    + WritePlan.decimalProblem(d, binding.precision(), binding.scale()) + " (" + kept + "), and is "
                    + "never rounded. Round or filter it with a compute node, or ask for the column to be widened",
                    column, "too_precise", "the value has more digits than the column keeps (" + kept + ") and is "
                    + "never rounded; round or filter it with a compute node, or ask for the column to be widened");
            }
            return exact;
        }
        Integer digits = fractionDigits.get(binding.target());
        if (digits != null) {
            int nanos = switch (v) {
                case java.time.LocalDateTime t -> t.getNano();
                case java.time.Instant t -> t.getNano();
                case java.time.LocalTime t -> t.getNano();
                default -> 0;
            };
            if (nanos % (int) Math.pow(10, 9 - digits) != 0) {
                throw new Refused("Row " + rowNumber + ": the value in column " + binding.target()
                    + " has more digits of a second than the column keeps (" + digits + "), and is never rounded. "
                    + "Truncate it with a compute node, or ask for the column to keep more", column, "too_precise",
                    "the value has more digits of a second than the column keeps (" + digits + ") and is never "
                    + "rounded; truncate it with a compute node, or ask for the column to keep more");
            }
        }
        TargetColumn into = targets.get(binding.target());
        String misfit = sql.targetValueProblem(into, v);
        if (misfit != null) {
            throw new Refused("Row " + rowNumber + ", column " + binding.target() + ": " + misfit, column,
                "does_not_fit", "the value does not fit what the column holds (its type or size)");
        }
        if (v instanceof String s) {
            if (binding.maxLength() > 0 && sql.length(s) > binding.maxLength()) {
                throw new Refused("Row " + rowNumber + ": the value in column " + binding.target() + " is "
                    + sql.length(s) + " characters long, but the column holds at most " + binding.maxLength()
                    + ". Shorten the values with a compute node, or ask for the column to be widened", column,
                    "too_long", "the value is longer than the column holds (at most " + binding.maxLength()
                    + " characters); shorten such values with a compute node, or ask for the column to be widened");
            }
        }
        return v;
    }

    // ------------------------------------------------------------------ loaders

    /** Sends rows to the table. */
    private interface Loader {

        /** Sends rows, each its bound values, converted and checked ({@link #rowValues}). */
        void write(Object[][] rows) throws SQLException, NodeException;

        /** Sends what is buffered, so a commit includes it. */
        void flush() throws SQLException;

        /** Stops sending (after a failure) and releases what it holds; called once at the end, whatever happened. Never fails. */
        void release();
    }

    /** PostgreSQL's {@code COPY … FROM STDIN}, as CSV, one {@code COPY} per transaction. */
    private final class CopyLoader implements Loader {

        private final String copySql;
        private org.postgresql.copy.CopyIn copy;

        CopyLoader(String copySql) {
            this.copySql = copySql;
        }

        @Override
        public void write(Object[][] values) throws SQLException, NodeException {
            int n = values.length;
            if (n == 0) {
                return;
            }
            StringBuilder b = new StringBuilder(n * 32);
            for (Object[] row : values) {
                for (int c = 0; c < bindings.size(); c++) {
                    if (c > 0) {
                        b.append(',');
                    }
                    WriteSql.Postgres.copyValue(b, bindings.get(c).kind(), row[c]);
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
        public void release() {
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

    /**
     * MySQL's {@code LOAD DATA LOCAL INFILE}, for tables this node created: the rows are sent from
     * memory as the statement's data ({@code setLocalInfileInputStream}), never from a file, in
     * loads of {@code batch_size} rows. With LOCAL the server turns bad values into warnings rather
     * than errors, so a load that warns, or loads another number of rows than were sent, fails.
     */
    private final class LoadDataLoader implements Loader {

        private final MySqlWriteSql my;
        private final String statement;
        private StringBuilder rows = new StringBuilder();
        private int pending;

        LoadDataLoader(MySqlWriteSql my, List<String> names) {
            this.my = my;
            this.statement = my.loadData(loadInto, names, bindings.stream().map(Binding::kind).toList());
        }

        @Override
        public void write(Object[][] values) throws SQLException, NodeException {
            for (Object[] row : values) {
                for (int c = 0; c < bindings.size(); c++) {
                    if (c > 0) {
                        rows.append(',');
                    }
                    my.loadField(rows, bindings.get(c).kind(), row[c]);
                }
                rows.append('\n');
                if (++pending >= batchSize || rows.length() >= MySqlWriteSql.LOAD_CHARS) {
                    send();
                }
            }
        }

        private void send() throws SQLException {
            if (pending == 0) {
                return;
            }
            byte[] data = rows.toString().getBytes(StandardCharsets.UTF_8);
            int sent = pending;
            rows = new StringBuilder();
            pending = 0;
            try (Statement s = connection.createStatement()) {
                s.unwrap(org.mariadb.jdbc.Statement.class).setLocalInfileInputStream(new java.io.ByteArrayInputStream(data));
                long loaded = s.executeLargeUpdate(statement);
                java.sql.SQLWarning w = s.getWarnings();
                // @@warning_count counts every warning, even where max_error_count keeps getWarnings() empty
                long warnings = w == null ? 0 : 1;
                try (Statement count = connection.createStatement();
                     ResultSet rs = count.executeQuery("SELECT @@warning_count")) {
                    if (rs.next()) {
                        warnings = Math.max(warnings, rs.getLong(1));
                    }
                }
                if (warnings > 0 || loaded != sent) {
                    throw new SQLException("The database loaded " + loaded + " of " + sent + " rows"
                        + (warnings == 0 ? "" : " with " + warnings + " warning" + (warnings == 1 ? "" : "s")
                        + (w == null ? "" : ", the first: " + w.getMessage())));
                }
            }
        }

        @Override
        public void flush() throws SQLException {
            send();
        }

        @Override
        public void release() {
            rows = new StringBuilder();
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
        public void write(Object[][] values) throws SQLException, NodeException {
            if (ps == null) {
                ps = connection.prepareStatement(statement);
            }
            for (Object[] row : values) {
                for (int c = 0; c < bindings.size(); c++) {
                    Binding binding = bindings.get(c);
                    sql.bind(ps, c + 1, binding.kind(), row[c], bulk, targets.get(binding.target()));
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
        public void release() {
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

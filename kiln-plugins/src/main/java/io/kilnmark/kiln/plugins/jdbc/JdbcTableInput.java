package io.kilnmark.kiln.plugins.jdbc;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import org.apache.arrow.vector.FieldVector;
import org.apache.arrow.vector.VectorSchemaRoot;
import org.apache.arrow.vector.types.pojo.Field;
import org.apache.arrow.vector.types.pojo.Schema;

import io.kilnmark.kiln.MetadataEvent;
import io.kilnmark.kiln.Node;
import io.kilnmark.kiln.NodeContext;
import io.kilnmark.kiln.NodeException;

/**
 * Reads a database table into Arrow batches.
 *
 * <p>Settings: {@code url}, {@code user}, {@code password}, driver properties as
 * {@code driver.}<i>name</i> (for example {@code driver.trustServerCertificate}), either {@code query} (with
 * {@code label} naming what it reads) or {@code schema} and {@code table}, {@code asset-urn}
 * (for the row count it reports), {@code batch-size} (default 8192) and {@code row-limit}
 * (default 0, every row; with {@code query}, the query must apply the limit itself) and
 * {@code transaction-isolation} (a {@link Connection} isolation level number; default the
 * driver's). When it finishes it reports the rows it read as a {@code ROW_COUNT} event, marked
 * complete unless a row limit cut the read short.
 *
 * <p>Sampling large tables: with {@link #SAMPLE_ABOVE} (rows), {@link #ESTIMATE_QUERY} (giving the
 * database's row estimate) and {@link #SAMPLE_QUERY} (holding {@link #PERCENT}), a table estimated
 * above that many rows is read through the sample query instead, at the percentage that gives
 * about that many rows. The {@code ROW_COUNT} event then says {@code complete=false},
 * {@code sampled=true}, {@code sample_percent} and {@code estimated_rows}.
 *
 * <p>A database that connects or maps columns differently gives its own {@link Reading}; values it
 * reads as null because Arrow has no such value (MySQL's zero dates) are counted, and the
 * {@code ROW_COUNT} event then says {@code zero_dates}.
 */
public final class JdbcTableInput implements Node {

    /** How a database is connected to and its columns mapped, where it differs from plain JDBC. */
    public interface Reading {

        /**
         * A connection for the node's settings: {@code url}, {@code user}, {@code password} and
         * driver properties ({@code driver.}...).
         */
        default Connection open(Map<String, String> settings) throws SQLException {
            return DriverManager.getConnection(settings.get("url"), connectionProperties(settings));
        }

        /** How column {@code i} (1-based) of the result is read. */
        default ColumnReader column(ResultSetMetaData md, int i, Map<String, String> settings) throws SQLException {
            return JdbcColumn.of(md, i);
        }

        /**
         * Whether a driver that refuses {@code setReadOnly} or {@code setAutoCommit(false)} is still read
         * (generic JDBC: drivers that do not offer them); false by default, so the read fails.
         */
        default boolean tolerant() {
            return false;
        }

        /**
         * The query to run on {@code connection}, from the node's {@code sql}: the same by default.
         * A database whose identifiers are quoted as its driver says builds it here.
         */
        default String query(Connection connection, String sql, Map<String, String> settings) throws SQLException {
            return sql;
        }
    }

    /** How one column of a result becomes one Arrow column. */
    public interface ColumnReader {

        /** The Arrow field. */
        Field field();

        /** Copies column {@code col} (1-based) of the current row into {@code vector} at {@code row}. */
        void copy(ResultSet rs, int col, FieldVector vector, int row) throws SQLException;

        /** Dates with a zero year, month or day read as null so far: they have no Arrow value. */
        default long zeroDates() {
            return 0;
        }
    }

    /** Plain JDBC. */
    private static final Reading PLAIN = new Reading() {
    };

    private final Reading reading;

    /** A reader for databases that need nothing of their own. */
    public JdbcTableInput() {
        this(PLAIN);
    }

    /** A reader connecting and mapping columns as {@code reading} says. */
    public JdbcTableInput(Reading reading) {
        this.reading = reading;
    }

    public static final String TYPE = "jdbc-table-input";

    /** Settings starting with this are driver properties, passed to the driver without it. */
    public static final String DRIVER_PREFIX = "driver.";
    /** The setting holding the transaction isolation level the read runs at, as a JDBC level number. */
    public static final String ISOLATION = "transaction-isolation";
    /** Setting: sample tables estimated above this many rows. */
    public static final String SAMPLE_ABOVE = "sample-above";
    /** Setting: SQL giving the row estimate as one number. */
    public static final String ESTIMATE_QUERY = "estimate-query";
    /** Setting: SQL reading a sample; {@link #PERCENT} is replaced by the percentage. */
    public static final String SAMPLE_QUERY = "sample-query";
    /** Placeholder for the percentage in {@link #SAMPLE_QUERY}. */
    public static final String PERCENT = "{percent}";
    /**
     * Setting: the most rows the driver returns ({@link Statement#setMaxRows}), for databases whose
     * SQL has no portable row limit; the query then reads the whole table.
     */
    public static final String MAX_ROWS = "max-rows";
    /**
     * Settings: the values of a narrowed read's {@code ?}s, as {@code value.1}, {@code value.2}, ...
     * each {@code type:value} with type one of long, decimal, double, boolean, text, date.
     */
    static final String VALUE_PREFIX = "value.";

    /** Puts each value of a narrowed read into {@code settings}, in order. */
    static void putValues(Map<String, String> settings, List<Object> values) {
        for (int i = 0; i < values.size(); i++) {
            Object v = values.get(i);
            String type = switch (v) {
                case Long l -> "long";
                case Integer n -> "long";
                case java.math.BigDecimal d -> "decimal";
                case Double d -> "double";
                case Boolean b -> "boolean";
                case java.time.LocalDate d -> "date";
                case String t -> "text";
                default -> throw new IllegalArgumentException("a read cannot bind a " + v.getClass().getSimpleName());
            };
            settings.put(VALUE_PREFIX + (i + 1), type + ":" + v);
        }
    }

    /** The values {@link #putValues} put into {@code settings}, in order. */
    static List<Object> values(Map<String, String> settings) {
        List<Object> out = new ArrayList<>();
        for (int i = 1; settings.containsKey(VALUE_PREFIX + i); i++) {
            String s = settings.get(VALUE_PREFIX + i);
            int colon = s.indexOf(':');
            String v = s.substring(colon + 1);
            out.add(switch (s.substring(0, colon)) {
                case "long" -> Long.parseLong(v);
                case "decimal" -> new java.math.BigDecimal(v);
                case "double" -> Double.parseDouble(v);
                case "boolean" -> Boolean.parseBoolean(v);
                case "date" -> java.time.LocalDate.parse(v);
                default -> v;
            });
        }
        return out;
    }

    private static void bind(PreparedStatement p, int i, Object v) throws SQLException {
        switch (v) {
            case Long l -> p.setLong(i, l);
            case java.math.BigDecimal d -> p.setBigDecimal(i, d);
            case Double d -> p.setDouble(i, d);
            case Boolean b -> p.setBoolean(i, b);
            case java.time.LocalDate d -> p.setDate(i, java.sql.Date.valueOf(d));
            default -> p.setString(i, String.valueOf(v));
        }
    }

    private String samplePercent;
    private long estimatedRows = -1;

    private Connection connection;
    private Statement statement;
    private ResultSet rows;
    private List<ColumnReader> columns;
    private Schema schema;
    private int batchSize;
    private long rowLimit;
    private long read;
    private boolean exhausted;
    private String assetUrn;

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public void init(NodeContext context) throws NodeException {
        batchSize = Integer.parseInt(context.setting("batch-size", "8192"));
        rowLimit = Long.parseLong(context.setting("row-limit", "0"));
        // A query reads no one asset: its tables are recorded by the read node (ADR 0028), and it has no row count
        assetUrn = context.settings().containsKey("query") ? context.setting("asset-urn", null)
            : context.setting("asset-urn");
        String label;
        String sql;
        if (context.settings().containsKey("query")) {
            sql = context.setting("query");
            label = context.setting("label", "the query");
        } else {
            label = context.setting("schema") + "." + context.setting("table");
            sql = "SELECT * FROM " + quote(context.setting("schema")) + "." + quote(context.setting("table"))
                + (rowLimit > 0 ? " LIMIT " + rowLimit : "");
        }
        try {
            connection = reading.open(context.settings());
            try {
                connection.setReadOnly(true);
                connection.setAutoCommit(false); // PostgreSQL streams with a cursor only inside a transaction
            } catch (SQLException | RuntimeException e) {
                if (!reading.tolerant()) {
                    throw e;
                }
                // advice the driver does not take: the read is a SELECT either way
            }
            if (context.settings().containsKey("query")) {
                // A query a person wrote, checked as PostgreSQL reads it with standard_conforming_strings on:
                // it runs only with it on, whatever the database, login or URL says (ADR 0028).
                PostgresConnector.conformingStrings(connection);
            }
            if (context.settings().containsKey(ISOLATION)) {
                connection.setTransactionIsolation(Integer.parseInt(context.setting(ISOLATION)));
            }
            List<Object> values = values(context.settings());
            if (values.isEmpty()) {
                statement = connection.createStatement();
                if (context.settings().containsKey(SAMPLE_ABOVE)) {
                    sql = sampled(context, sql);
                }
                sql = reading.query(connection, sql, context.settings());
            } else {
                // A narrowed read: each value bound, never written into the SQL.
                PreparedStatement prepared = connection.prepareStatement(sql);
                statement = prepared; // closed with the read, even if binding fails
                for (int i = 0; i < values.size(); i++) {
                    bind(prepared, i + 1, values.get(i));
                }
            }
            int fetch = batchSize;
            if (context.settings().containsKey(MAX_ROWS)) {
                int max = (int) Math.min(Integer.MAX_VALUE, Long.parseLong(context.setting(MAX_ROWS)));
                statement.setMaxRows(max);
                fetch = Math.min(fetch, Math.max(1, max)); // JDBC: the fetch size may not exceed the row limit
            }
            statement.setFetchSize(fetch);
            rows = statement instanceof PreparedStatement p ? p.executeQuery() : statement.executeQuery(sql);
            ResultSetMetaData md = rows.getMetaData();
            columns = new ArrayList<>(md.getColumnCount());
            List<Field> fields = new ArrayList<>(md.getColumnCount());
            for (int i = 1; i <= md.getColumnCount(); i++) {
                ColumnReader c = reading.column(md, i, context.settings());
                columns.add(c);
                fields.add(c.field());
            }
            schema = new Schema(fields);
        } catch (SQLException e) {
            throw new NodeException("Cannot read " + label + ": " + e.getMessage(), e);
        }
    }

    /** The user, password and {@code driver.} settings as the driver's connection properties. */
    static Properties connectionProperties(Map<String, String> settings) {
        Properties p = new Properties();
        settings.forEach((k, v) -> {
            if (v != null && k.startsWith(DRIVER_PREFIX) && k.length() > DRIVER_PREFIX.length()) {
                p.setProperty(k.substring(DRIVER_PREFIX.length()), v);
            }
        });
        if (settings.get("user") != null) {
            p.setProperty("user", settings.get("user"));
        }
        if (settings.get("password") != null) {
            p.setProperty("password", settings.get("password"));
        }
        return p;
    }

    @Override
    public boolean process(NodeContext context) throws NodeException {
        VectorSchemaRoot batch = VectorSchemaRoot.create(schema, context.allocator());
        try {
            for (FieldVector v : batch.getFieldVectors()) {
                v.setInitialCapacity(batchSize);
            }
            batch.allocateNew();
            int n = 0;
            while (n < batchSize && rows.next()) {
                for (int c = 0; c < columns.size(); c++) {
                    columns.get(c).copy(rows, c + 1, batch.getVector(c), n);
                }
                n++;
            }
            exhausted = n < batchSize;
            batch.setRowCount(n);
            read += n;
        } catch (SQLException | RuntimeException e) {
            batch.close();
            throw new NodeException("Reading row " + (read + 1) + " failed: " + e.getMessage(), e);
        }
        try {
            if (batch.getRowCount() > 0 || read == 0) {
                context.output().put(batch); // an empty table still sends its schema once
            } else {
                batch.close();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new NodeException("Interrupted", e);
        }
        if (exhausted) {
            boolean complete = samplePercent == null && (rowLimit <= 0 || read < rowLimit);
            Map<String, String> attributes = new java.util.HashMap<>(
                Map.of("rows", Long.toString(read), "complete", Boolean.toString(complete)));
            if (samplePercent != null) {
                attributes.put("sampled", "true");
                attributes.put("sample_percent", samplePercent);
                attributes.put("estimated_rows", Long.toString(estimatedRows));
            }
            long zeroDates = columns.stream().mapToLong(ColumnReader::zeroDates).sum();
            if (zeroDates > 0) {
                attributes.put("zero_dates", Long.toString(zeroDates));
            }
            if (assetUrn != null) {
                context.metadata().emit(new MetadataEvent(assetUrn, MetadataEvent.Kind.ROW_COUNT, attributes,
                    Instant.now()));
            }
        }
        return !exhausted;
    }

    @Override
    public void close() {
        if (statement != null && !exhausted) {
            // Stopped early (a failure, or a node after this one gave up). Without a cancel some
            // drivers, SQL Server's among them, read every remaining row while closing the result.
            try {
                statement.cancel();
            } catch (SQLException | RuntimeException e) {
                // the query may already have ended; closing below still releases everything
            }
        }
        try {
            if (rows != null) rows.close();
            if (statement != null) statement.close();
            if (connection != null) {
                try {
                    connection.rollback();
                } catch (SQLException e) {
                    // in auto-commit (a driver that would not leave it): nothing to roll back
                }
                connection.close();
            }
        } catch (SQLException e) {
            // closing a read-only connection; nothing to keep
        }
    }

    /** The sample query if the table is estimated above the threshold, otherwise {@code sql}. */
    private String sampled(NodeContext context, String sql) throws SQLException, NodeException {
        long above = Long.parseLong(context.setting(SAMPLE_ABOVE));
        try (ResultSet r = statement.executeQuery(context.setting(ESTIMATE_QUERY))) {
            estimatedRows = r.next() ? r.getLong(1) : -1;
        }
        if (estimatedRows <= above) {
            return sql;
        }
        double percent = Math.max(0.0001, Math.min(100.0, 100.0 * above / estimatedRows));
        samplePercent = new java.math.BigDecimal(percent).round(new java.math.MathContext(3)).stripTrailingZeros()
            .toPlainString();
        return context.setting(SAMPLE_QUERY).replace(PERCENT, samplePercent);
    }

    static String quote(String identifier) {
        return "\"" + identifier.replace("\"", "\"\"") + "\"";
    }
}

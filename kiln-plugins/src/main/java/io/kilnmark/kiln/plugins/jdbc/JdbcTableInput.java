package io.kilnmark.kiln.plugins.jdbc;

import java.sql.Connection;
import java.sql.DriverManager;
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
import io.kilnmark.kiln.Step;
import io.kilnmark.kiln.StepContext;
import io.kilnmark.kiln.StepException;

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
 */
public final class JdbcTableInput implements Step {

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

    private String samplePercent;
    private long estimatedRows = -1;

    private Connection connection;
    private Statement statement;
    private ResultSet rows;
    private List<JdbcColumn> columns;
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
    public void init(StepContext context) throws StepException {
        batchSize = Integer.parseInt(context.setting("batch-size", "8192"));
        rowLimit = Long.parseLong(context.setting("row-limit", "0"));
        assetUrn = context.setting("asset-urn");
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
            connection = DriverManager.getConnection(context.setting("url"), connectionProperties(context.settings()));
            connection.setReadOnly(true);
            connection.setAutoCommit(false); // PostgreSQL streams with a cursor only inside a transaction
            if (context.settings().containsKey(ISOLATION)) {
                connection.setTransactionIsolation(Integer.parseInt(context.setting(ISOLATION)));
            }
            statement = connection.createStatement();
            if (context.settings().containsKey(SAMPLE_ABOVE)) {
                sql = sampled(context, sql);
            }
            statement.setFetchSize(batchSize);
            rows = statement.executeQuery(sql);
            ResultSetMetaData md = rows.getMetaData();
            columns = new ArrayList<>(md.getColumnCount());
            List<Field> fields = new ArrayList<>(md.getColumnCount());
            for (int i = 1; i <= md.getColumnCount(); i++) {
                JdbcColumn c = JdbcColumn.of(md, i);
                columns.add(c);
                fields.add(c.field());
            }
            schema = new Schema(fields);
        } catch (SQLException e) {
            throw new StepException("Cannot read " + label + ": " + e.getMessage(), e);
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
    public boolean process(StepContext context) throws StepException {
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
            throw new StepException("Reading row " + (read + 1) + " failed: " + e.getMessage(), e);
        }
        try {
            if (batch.getRowCount() > 0 || read == 0) {
                context.output().put(batch); // an empty table still sends its schema once
            } else {
                batch.close();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new StepException("Interrupted", e);
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
            context.metadata().emit(new MetadataEvent(assetUrn, MetadataEvent.Kind.ROW_COUNT, attributes, Instant.now()));
        }
        return !exhausted;
    }

    @Override
    public void close() {
        if (statement != null && !exhausted) {
            // Stopped early (a failure, or a step after this one gave up). Without a cancel some
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
                connection.rollback();
                connection.close();
            }
        } catch (SQLException e) {
            // closing a read-only connection; nothing to keep
        }
    }

    /** The sample query if the table is estimated above the threshold, otherwise {@code sql}. */
    private String sampled(StepContext context, String sql) throws SQLException, StepException {
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

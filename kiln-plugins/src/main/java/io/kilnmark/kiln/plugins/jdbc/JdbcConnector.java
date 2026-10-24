package io.kilnmark.kiln.plugins.jdbc;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

import io.kilnmark.kiln.StepSpec;
import io.kilnmark.kiln.connect.AssetInfo;
import io.kilnmark.kiln.connect.Connector;
import io.kilnmark.kiln.connect.ConnectorException;
import io.kilnmark.kiln.connect.SourceConfig;

/**
 * What every JDBC database shares: settings {@code url}, {@code username}, {@code schemas}; the
 * catalogue from JDBC metadata; reading through {@link JdbcTableInput}. A database adds its URL
 * prefix and the SQL that reads a table, with or without a row limit.
 */
public abstract class JdbcConnector implements Connector {

    /** A password written into a URL: {@code ;password=} (SQL Server) or {@code ?password=} / {@code &password=}. */
    private static final Pattern PASSWORD_IN_URL = Pattern.compile("[;?&]\\s*password\\s*=", Pattern.CASE_INSENSITIVE);

    /** The JDBC URL prefix this database's driver accepts, for example {@code jdbc:postgresql://}. */
    protected abstract String urlPrefix();

    /** An example URL for hints and error messages. */
    protected abstract String exampleUrl();

    /** SQL reading every column of {@code schema.table}, at most {@code rowLimit} rows when above 0. */
    protected abstract String selectSql(String schema, String table, long rowLimit);

    /**
     * SQL reading {@code asset}, at most {@code rowLimit} rows when above 0. By default
     * {@link #selectSql(String, String, long)} on the schema and table hints; override to choose
     * how particular columns are read.
     */
    protected String selectSql(AssetInfo asset, long rowLimit) {
        return selectSql(asset.hints().get("schema"), asset.hints().get("table"), rowLimit);
    }

    /**
     * The tables and views in {@code schemas} (every user schema when empty). By default standard
     * JDBC metadata; override to add what the driver does not report, such as comments.
     */
    protected List<JdbcCatalog.Table> readCatalog(Connection connection, List<String> schemas) throws SQLException {
        return JdbcCatalog.read(connection, schemas, new JdbcCatalog.Dialect() {
            @Override
            public boolean isSystemSchema(String schema) {
                return JdbcConnector.this.isSystemSchema(schema);
            }

            @Override
            public String schemaPattern(DatabaseMetaData md, String schema) throws SQLException {
                return JdbcConnector.this.schemaPattern(md, schema);
            }
        });
    }

    /**
     * True for a schema that belongs to the database itself, which a scan of every schema skips.
     * By default only {@code information_schema}; each database lists its own.
     */
    protected boolean isSystemSchema(String schema) {
        return schema.equalsIgnoreCase("information_schema");
    }

    /**
     * {@code schema} as a metadata search pattern matching only itself. By default its wildcards
     * are escaped with the driver's search-string escape; override where the driver differs.
     */
    protected String schemaPattern(DatabaseMetaData md, String schema) throws SQLException {
        return JdbcCatalog.escapeWithDriver(md, schema);
    }

    /** Extra settings for {@link JdbcTableInput} when reading from {@code source}; none by default. */
    protected Map<String, String> readSettings(SourceConfig source) {
        return Map.of();
    }

    /**
     * What a person should read when connecting or reading the catalogue fails: by default the
     * driver's message; override to add what to do about failures this database is known for.
     */
    protected String explain(SQLException e) {
        return e.getMessage();
    }

    /** Quotes an identifier the standard way; override for databases that differ. */
    protected String quote(String identifier) {
        return "\"" + identifier.replace("\"", "\"\"") + "\"";
    }

    /**
     * Setting: tables with more rows than this (by the database's own estimate) are profiled from a
     * {@code TABLESAMPLE} of about that many rows, and their profile is marked as an estimate.
     * Absent: every row is read. Like {@code keep_samples}, it is one of the source's common
     * settings rather than a form field of each database's connector.
     */
    public static final String TABLE_SAMPLE_ABOVE = "table_sample_above_rows";

    /** How this database writes SQL, for checks Kilnmark runs in it. */
    public SqlDialect dialect() {
        return SqlDialect.POSTGRESQL;
    }

    /** A connection to the source, for checks run in the database; the caller closes it. */
    public Connection connect(SourceConfig source) throws SQLException {
        return DriverManager.getConnection(source.setting("url"), source.setting("username"), source.secret());
    }

    /**
     * SQL giving the database's estimate of {@code asset}'s row count as one number, or null when
     * this database has none (then tables are never sampled).
     */
    protected String estimateRowsSql(AssetInfo asset) {
        return null;
    }

    /** The {@code TABLESAMPLE} clause for {@code percent} (a number as text), or null when unsupported. */
    protected String tableSampleClause(String percent) {
        return null;
    }

    @Override
    public List<Setting> settings() {
        return List.of(
            new Setting("url", "JDBC URL", true, "For example " + exampleUrl(), false),
            new Setting("username", "User name", false, "A read-only account is enough", false),
            new Setting("schemas", "Schemas", false, "Leave empty to scan every schema", true));
    }

    @Override
    public List<String> validate(SourceConfig source) {
        List<String> problems = new ArrayList<>();
        String url = source.setting("url");
        if (url == null || !url.startsWith(urlPrefix())) {
            problems.add("url: a " + displayName() + " JDBC URL, for example " + exampleUrl());
        } else if (PASSWORD_IN_URL.matcher(url).find()) {
            problems.add("url: take the password out of the URL and enter it as the source's password instead; "
                + "the URL is stored and shown as plain text, the password is encrypted");
        }
        String above = source.setting(TABLE_SAMPLE_ABOVE);
        if (above != null) {
            try {
                if (Long.parseLong(above) < 1000) {
                    problems.add(TABLE_SAMPLE_ABOVE + ": at least 1000 rows");
                }
            } catch (NumberFormatException e) {
                problems.add(TABLE_SAMPLE_ABOVE + ": a whole number of rows, for example 10000000");
            }
        }
        return problems;
    }

    @Override
    public List<AssetInfo> catalog(SourceConfig source) throws ConnectorException {
        try (Connection c = DriverManager.getConnection(source.setting("url"), source.setting("username"),
            source.secret())) {
            List<AssetInfo> out = new ArrayList<>();
            for (JdbcCatalog.Table t : readCatalog(c, source.list("schemas"))) {
                List<AssetInfo.FieldInfo> fields = t.columns().stream().map(col -> new AssetInfo.FieldInfo(col.name(),
                    col.position(), col.sourceType(), col.kind().name().toLowerCase(Locale.ROOT), col.nullable(),
                    col.comment())).toList();
                boolean table = t.type().equals("table") || t.type().equals("partitioned table");
                out.add(new AssetInfo(t.path(), t.type(), t.schema(), t.name(), t.comment(), fields, t.primaryKey(),
                    !table, true, Map.of("schema", t.schema(), "table", t.name())));
            }
            return out;
        } catch (SQLException e) {
            throw new ConnectorException("Cannot read the catalogue of " + source.name() + ": " + explain(e), e);
        }
    }

    @Override
    public StepSpec reader(SourceConfig source, AssetInfo asset, ReadOptions read) {
        Map<String, String> s = new HashMap<>();
        s.put("url", source.setting("url"));
        if (source.setting("username") != null) {
            s.put("user", source.setting("username"));
        }
        if (source.secret() != null) {
            s.put("password", source.secret());
        }
        s.put("query", selectSql(asset, read.rowLimit()));
        s.put("label", asset.path());
        s.put("asset-urn", read.assetUrn());
        s.put("row-limit", Long.toString(read.rowLimit()));
        s.put("batch-size", Integer.toString(read.batchSize()));
        String above = source.setting(TABLE_SAMPLE_ABOVE);
        if (above != null && !asset.bounded() && read.rowLimit() <= 0 && asset.hints().containsKey("table")) {
            String estimate = estimateRowsSql(asset);
            String clause = tableSampleClause(JdbcTableInput.PERCENT);
            if (estimate != null && clause != null) {
                s.put(JdbcTableInput.SAMPLE_ABOVE, above);
                s.put(JdbcTableInput.ESTIMATE_QUERY, estimate);
                s.put(JdbcTableInput.SAMPLE_QUERY, selectSql(asset, 0) + " " + clause);
            }
        }
        s.putAll(readSettings(source));
        return new StepSpec("read", new JdbcTableInput(), s);
    }
}

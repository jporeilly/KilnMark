package io.kilnmark.kiln.connect;

import java.util.List;

import io.kilnmark.kiln.StepSpec;

/**
 * A kind of data source: how to list what it holds, and how to read one asset as Arrow batches.
 *
 * <p>Connectors are plug-ins, found with {@link java.util.ServiceLoader}: each jar lists its
 * connectors in {@code META-INF/services/io.kilnmark.kiln.connect.Connector}. The server knows
 * nothing about any particular kind of source; it asks the {@link Connectors} registry.
 *
 * <p>Implementations must be stateless and thread-safe: one instance serves every source of its
 * kind, and a scan reads several assets at once.
 */
public interface Connector {

    /** Stable identifier stored with each source, for example {@code postgresql}, {@code s3}. */
    String kind();

    /** A short human name, for example "PostgreSQL". */
    String displayName();

    /** The settings a source of this kind takes, in the order a form should show them. */
    List<Setting> settings();

    /**
     * Problems with a source's settings, without connecting to anything; empty when acceptable.
     * Each problem starts with the setting it is about, for example {@code "url: ..."}.
     */
    List<String> validate(SourceConfig source);

    /**
     * Everything the source holds that Kilnmark should record: tables, views, files and their
     * fields. Paths must be unique within the source; they become the asset URN's path.
     *
     * @throws ConnectorException when the source cannot be reached or read, with a message a
     *                            person can act on
     */
    List<AssetInfo> catalog(SourceConfig source) throws ConnectorException;

    /**
     * The first step of the pipeline that reads {@code asset}: it puts Arrow batches on its output
     * and, when it finishes, reports the rows it read as a {@code ROW_COUNT} event for
     * {@code read.assetUrn()} with attributes {@code rows} and {@code complete}. The step's
     * settings may hold the source's secret; they are never logged.
     */
    StepSpec reader(SourceConfig source, AssetInfo asset, ReadOptions read);

    /**
     * This connector set up with the server's settings for connectors. The registry calls it once,
     * when it loads the connectors, and keeps what it returns; a connector that needs server-wide
     * settings (allowed folders, credential policy) returns a new, equally immutable instance.
     * The default returns this connector unchanged.
     */
    default Connector withContext(ConnectorContext context) {
        return this;
    }

    /**
     * A scan of {@code source} has ended, however it ended: the connector may release what it kept
     * for the scan (local copies of remote files, say). The default does nothing.
     */
    default void finished(SourceConfig source) {
        // most connectors keep nothing between reads
    }

    /** How much of an asset to read. {@code rowLimit} 0 reads everything. */
    record ReadOptions(String assetUrn, long rowLimit, int batchSize) {
    }

    /** One setting a source of this kind takes. */
    record Setting(String key, String label, boolean required, String hint, boolean multiple) {
    }
}

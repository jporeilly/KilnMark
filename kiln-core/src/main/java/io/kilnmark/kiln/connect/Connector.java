package io.kilnmark.kiln.connect;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

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

    // ------------------------------------------------------------------ choosing what to scan

    /**
     * How this kind of source groups its assets, so people can choose which groups to scan. The
     * default: a list setting {@code containers} holding names as {@link #containerOf} reports them.
     */
    default ContainerKind containerKind() {
        return new ContainerKind("containers", "Containers", "assets");
    }

    /**
     * The container an asset belongs to, as {@link #containers} names it and the container setting
     * selects it. The default is the asset's own {@link AssetInfo#container()}.
     */
    default String containerOf(SourceConfig source, AssetInfo asset) {
        return asset.container() == null ? "" : asset.container();
    }

    /**
     * Every container the source holds, with how many assets in each the source's
     * {@link AssetFilter patterns} keep, whatever containers are chosen. Should be cheap: database
     * metadata or a listing, not a full catalogue. The default reads the full catalogue, so
     * connectors override it.
     *
     * @throws ConnectorException when the source cannot be reached, with a message a person can act on
     */
    default List<ContainerInfo> containers(SourceConfig source) throws ConnectorException {
        Map<String, String> every = new HashMap<>(source.settings());
        every.remove(containerKind().setting());
        every.remove(AssetFilter.INCLUDE);
        every.remove(AssetFilter.EXCLUDE);
        SourceConfig all = new SourceConfig(source.name(), source.kind(), every, source.secret());
        AssetFilter patterns = AssetFilter.of(this, source).allContainers();
        Map<String, Long> counts = new TreeMap<>();
        for (AssetInfo a : catalog(all)) {
            counts.merge(containerOf(all, a), patterns.patterns(a.path(), a.name()) ? 1L : 0L, Long::sum);
        }
        return counts.entrySet().stream().map(e -> new ContainerInfo(e.getKey(), e.getValue())).toList();
    }

    /**
     * Checks that the source can be reached with its settings and secret, as cheaply as possible.
     * The default lists its {@link #containers}.
     *
     * @throws ConnectorException saying what is wrong, in words a person can act on; never holding the secret
     */
    default void test(SourceConfig source) throws ConnectorException {
        containers(source);
    }

    /**
     * The settings that decide where the source's secret is sent (a URL, an endpoint, a user
     * name). Changing one of them on a saved source needs the secret entered again, so a stored
     * secret is never sent somewhere new. The default is every setting.
     */
    default Set<String> secretScope() {
        Set<String> keys = new LinkedHashSet<>();
        settings().forEach(s -> keys.add(s.key()));
        return keys;
    }

    /**
     * Settings that point a source at a host, port and database, for bulk files that give them
     * apart (for example a JDBC {@code url}). Empty when this kind has no such address; the
     * default.
     *
     * @param port null for the kind's usual port
     */
    default Map<String, String> addressSettings(String host, Integer port, String database) {
        return Map.of();
    }

    /**
     * How a kind of source groups its assets.
     *
     * @param setting the list setting holding the chosen containers, for example {@code schemas}
     * @param label   what people call a container, plural: "Schemas", "Folders"
     * @param counted what a container's count counts: "tables and views", "files"
     */
    record ContainerKind(String setting, String label, String counted) {
    }

    /** One container and how many assets in it the source's patterns keep. */
    record ContainerInfo(String name, long count) {
    }

    /** How much of an asset to read. {@code rowLimit} 0 reads everything. */
    record ReadOptions(String assetUrn, long rowLimit, int batchSize) {
    }

    /** One setting a source of this kind takes. */
    record Setting(String key, String label, boolean required, String hint, boolean multiple) {
    }
}

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
     * The catalogue together with the relationships the source declares between its assets, such
     * as foreign keys, read in one go. The default has no relationships: it returns
     * {@link #catalog(SourceConfig)} alone. Connectors for sources that declare relationships
     * (databases) override it; the scan calls this method, not {@link #catalog(SourceConfig)}.
     *
     * @throws ConnectorException as {@link #catalog(SourceConfig)}
     */
    default CatalogInfo catalogWithRelationships(SourceConfig source) throws ConnectorException {
        return new CatalogInfo(catalog(source), List.of());
    }

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
     * {@link #containers(SourceConfig)} within {@code timeout}: a connector that can bound its own
     * network waits (driver timeouts, a listing that stops at a deadline) overrides this. The server
     * also abandons and interrupts a call that runs over. The default ignores the timeout.
     */
    default List<ContainerInfo> containers(SourceConfig source, java.time.Duration timeout) throws ConnectorException {
        return containers(source);
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

    /** {@link #test(SourceConfig)} within {@code timeout}; see {@link #containers(SourceConfig, java.time.Duration)}. */
    default void test(SourceConfig source, java.time.Duration timeout) throws ConnectorException {
        test(source);
    }

    /**
     * The settings that hold the source's network address (a JDBC {@code url}, an S3
     * {@code endpoint}): the only ones bulk import's host map rewrites. None by default.
     */
    default Set<String> addressKeys() {
        return Set.of();
    }

    /**
     * Every network host the source would connect to, for the server's allow-list of hosts. The
     * default reads the host names from the URLs in {@link #addressKeys()}.
     */
    default List<String> hosts(SourceConfig source) {
        List<String> hosts = new java.util.ArrayList<>();
        for (String key : addressKeys()) {
            hosts.addAll(UrlHosts.hosts(source.setting(key)));
        }
        return hosts;
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
     * @param setting   the list setting holding the chosen containers, for example {@code schemas}
     * @param label     what people call a container, plural: "Schemas", "Folders"
     * @param counted   what a container's count counts: "tables and views", "files"
     * @param separator what divides an asset's path: {@code .} for {@code schema.table}, {@code /}
     *                  for files. Patterns holding it match whole paths, others names only
     */
    record ContainerKind(String setting, String label, String counted, String separator) {

        /** A kind whose asset paths are divided by {@code /}. */
        public ContainerKind(String setting, String label, String counted) {
            this(setting, label, counted, "/");
        }
    }

    /** One container and how many assets in it the source's patterns keep. */
    record ContainerInfo(String name, long count) {
    }

    // ------------------------------------------------------------------ the catalogue of connectors

    /**
     * The group people find this kind of source under: {@code databases}, {@code nosql},
     * {@code files}, {@code streaming}, {@code applications} or {@code lakehouse}. The default,
     * {@code other}, is listed only under All.
     */
    default String category() {
        return "other";
    }

    /** A sub-group within the category, such as relational, object storage, message broker. */
    default String family() {
        return "other";
    }

    /** What the connector can do today. The default: discover and profile, and read. */
    default Set<Capability> capabilities() {
        return Set.of(Capability.DISCOVER, Capability.READ);
    }

    /** For file and stream connectors, the formats it reads (for example csv, parquet); none by default. */
    default List<String> formats() {
        return List.of();
    }

    /** A name from the UI's own icon set (never a vendor logo); the category by default. */
    default String icon() {
        return category();
    }

    /**
     * Two or three letters the UI shows on a tinted tile for a product, for example {@code Pg} or
     * {@code SQL}; never a vendor logo. Null (the default) for generic kinds such as a folder: the UI
     * then shows the {@link #icon()} on the tile. Operators can replace either with a logo they are
     * licensed to use, through the branding folder.
     */
    default String monogram() {
        return null;
    }

    /** The section of the connector documentation about this kind; its kind by default. */
    default String docsAnchor() {
        return kind();
    }

    /**
     * The driver this connector needs that does not ship with Kilnmark, or null when everything it
     * needs is bundled (the default). Installing drivers arrives with the first connector wave.
     */
    default Driver driver() {
        return null;
    }

    /** An example value for a setting, for forms; null when the setting's hint is enough (the default). */
    default String example(String settingKey) {
        return null;
    }

    /** What a connector can do with a source. */
    enum Capability {
        /** List and profile what it holds. */
        DISCOVER,
        /** Read rows in pipelines. */
        READ,
        /** Write rows in pipelines. */
        WRITE,
        /** Read continuously as data arrives. */
        STREAM
    }

    /**
     * A driver to be installed by the customer.
     *
     * @param coordinates Maven coordinates, group:artifact
     * @param version     the pinned version
     * @param sha256      the jar's checksum
     * @param licence     the licence's name
     * @param licenceUrl  where to read it
     * @param bundleable  whether Kilnmark may ship it
     */
    record Driver(String coordinates, String version, String sha256, String licence, String licenceUrl,
                  boolean bundleable) {
    }

    // ------------------------------------------------------------------ looking before scanning

    /**
     * The tables, views or files in one container (as {@link #containers} names it), without their
     * fields, for people to pick one to preview. The default reads the full catalogue, so
     * connectors override it with metadata or a listing.
     */
    default List<AssetInfo> assets(SourceConfig source, String container) throws ConnectorException {
        Map<String, String> every = new HashMap<>(source.settings());
        every.remove(containerKind().setting());
        SourceConfig all = new SourceConfig(source.name(), source.kind(), every, source.secret());
        return catalog(all).stream().filter(a -> containerOf(all, a).equals(container)).toList();
    }

    /**
     * One asset as the catalogue would record it, with its fields, by its path. The default reads
     * the full catalogue, so connectors override it.
     *
     * @throws ConnectorException when there is no such asset, or the source cannot be read
     */
    default AssetInfo asset(SourceConfig source, String path) throws ConnectorException {
        Map<String, String> every = new HashMap<>(source.settings());
        every.remove(containerKind().setting());
        every.remove(AssetFilter.INCLUDE);
        every.remove(AssetFilter.EXCLUDE);
        SourceConfig all = new SourceConfig(source.name(), source.kind(), every, source.secret());
        return catalog(all).stream().filter(a -> a.path().equals(path)).findFirst()
            .orElseThrow(() -> new ConnectorException("There is no table or file " + path + " in " + source.name()));
    }

    /**
     * The first {@code rows} rows of an asset (at most {@value Preview#MAX_ROWS}), read live with
     * {@link #reader} and a row limit, so every connector has a preview without writing one.
     * Override only to add {@link Preview#details()}.
     */
    default Preview preview(SourceConfig source, AssetInfo asset, int rows) throws ConnectorException {
        return Preview.read(this, source, asset, rows);
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

    /**
     * A step that writes the rows it receives on its {@code in} port to {@code source}
     * (docs/design/pipelines.md, section 4). Connectors that can write list
     * {@link Capability#WRITE} in {@link #capabilities()} and override this; the default refuses.
     * The caller (the pipeline service) has already checked that the source allows writing and
     * that its hosts are allowed.
     *
     * @param write what to write and how; see {@link WriteOptions}
     * @throws ConnectorException if this connector cannot write, or the options are wrong for it
     */
    default io.kilnmark.kiln.Step writer(SourceConfig source, WriteOptions write) throws ConnectorException {
        throw new ConnectorException(displayName() + " sources cannot be written to yet");
    }

    /**
     * The further settings a {@code write} step to this kind of source accepts (the keys of
     * {@link WriteOptions#options()}), with their kind, default and help, so the pipeline service
     * can pass only these and the canvas can show them. Empty (the default) for connectors that
     * cannot write.
     */
    default List<io.kilnmark.kiln.SettingSpec> writeOptions() {
        return List.of();
    }

    /**
     * Problems with a write to this source, found without connecting to anything: the target, the
     * mode, the settings, and whether the source can read back what would be written (for example a
     * CSV delimiter other than the source's). Empty when the write may be tried. Each problem starts
     * with the setting it is about. The default asks {@link #writer} for the step and reports its
     * refusal; connectors whose {@code writer} connects override it.
     */
    default List<String> checkWrite(SourceConfig source, WriteOptions write) {
        try {
            writer(source, write);
            return List.of();
        } catch (ConnectorException e) {
            return List.of(e.getMessage());
        }
    }

    /**
     * The path the asset written by {@code write} has in this source's catalogue, the same a scan
     * gives it, so the server records the write under the URN a later scan finds
     * ({@code urn:kilnmark:<source>:<path>}). The default is the target as given ({@code schema.table}
     * for databases); file connectors add the source's prefix and escape as their catalogue does.
     *
     * @throws ConnectorException if the target is not one this connector writes
     */
    default String writtenAssetPath(SourceConfig source, WriteOptions write) throws ConnectorException {
        return write.target();
    }

    /**
     * Where and how a pipeline's {@code write} step writes.
     *
     * @param target    a table ({@code schema.table}) for databases, or a path inside the source for
     *                  files and object storage
     * @param mode      {@code create}, {@code append}, {@code replace} or {@code upsert}
     * @param key       the key columns for {@code upsert}; empty otherwise
     * @param format    for files: {@code csv} or {@code parquet}; null for databases
     * @param options   any further step settings from the pipeline YAML, as written; only the keys in
     *                  {@link Connector#writeOptions()} are read
     * @param assetUrn  the URN the written asset is recorded under, for metadata and lineage
     * @param profiling how to profile what is written, set by the server from the source's own
     *                  profiling settings: the settings a scan gives the profile step
     *                  ({@code keep-values}, {@code examples}, {@code sample-size},
     *                  {@code sample-mode}, {@code sample-key}, {@code sample-seed},
     *                  {@code sample-max-bytes}, {@code sketch-fields}); never from the YAML.
     *                  Empty: the profile step's defaults
     */
    record WriteOptions(String target, String mode, java.util.List<String> key, String format,
                        Map<String, Object> options, String assetUrn, Map<String, String> profiling) {
        public WriteOptions {
            key = key == null ? java.util.List.of() : java.util.List.copyOf(key);
            options = options == null ? Map.of() : Map.copyOf(options);
            profiling = profiling == null ? Map.of() : Map.copyOf(profiling);
        }

        /** Without profiling settings: the profile step's defaults apply. */
        public WriteOptions(String target, String mode, java.util.List<String> key, String format,
                            Map<String, Object> options, String assetUrn) {
            this(target, mode, key, format, options, assetUrn, Map.of());
        }
    }

    /** One setting a source of this kind takes. */
    record Setting(String key, String label, boolean required, String hint, boolean multiple) {
    }
}

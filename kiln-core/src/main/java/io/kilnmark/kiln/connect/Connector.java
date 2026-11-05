package io.kilnmark.kiln.connect;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import io.kilnmark.kiln.NodeSpec;

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
     * The first node of the pipeline that reads {@code asset}: it puts Arrow batches on its output
     * and, when it finishes, reports the rows it read as a {@code ROW_COUNT} event for
     * {@code read.assetUrn()} with attributes {@code rows} and {@code complete}. The node's
     * settings may hold the source's secret; they are never logged.
     */
    NodeSpec reader(SourceConfig source, AssetInfo asset, ReadOptions read);

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
     * Why a connection to {@code source} could cross the network unencrypted, naming the setting to
     * change, or null when every connection is encrypted (or fails) or the source reaches no network,
     * for servers that require TLS to sources (ADR 0034). The default cannot tell, so it gives a
     * reason: a connector that reaches the network says for itself how its connections are encrypted.
     */
    default String unencryptedReason(SourceConfig source) {
        return "Kilnmark cannot tell whether " + displayName() + " connections are encrypted";
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
     * needs is bundled (the default). The customer installs it (docs/design/drivers.md): an admin
     * accepts its licence and the server downloads that exact version, or puts the file in the
     * drivers folder; the connector then gets it through {@link ConnectorContext#drivers()}.
     */
    default Driver driver() {
        return null;
    }

    /**
     * Whether only admins may set up this kind of source, change its connector settings, or check an
     * unsaved one: true where the settings go to code Kilnmark does not know, such as a JDBC URL
     * handed to a driver an admin registered, whose features (reading files, running scripts) Kilnmark
     * cannot vet. Stewards may still test, scan and preview saved sources. False by default.
     */
    default boolean adminOnly() {
        return false;
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
     * A driver to be installed by the customer: one jar, pinned by version and checksum, loaded on
     * its own ({@link DriverFolder}).
     *
     * @param coordinates Maven coordinates, group:artifact
     * @param version     the pinned version
     * @param sha256      the jar's SHA-256, lower-case hex
     * @param licence     the licence's name
     * @param licenceUrl  where to read it
     * @param bundleable  whether Kilnmark may ship it
     * @param driverClass the {@link java.sql.Driver} class in the jar, for JDBC drivers; null otherwise
     */
    record Driver(String coordinates, String version, String sha256, String licence, String licenceUrl,
                  boolean bundleable, String driverClass) {

        private static final java.util.regex.Pattern PART = java.util.regex.Pattern.compile("[A-Za-z0-9_.-]+");

        public Driver {
            String[] ga = coordinates == null ? new String[0] : coordinates.split(":");
            if (ga.length != 2 || !PART.matcher(ga[0]).matches() || !PART.matcher(ga[1]).matches()
                || version == null || !PART.matcher(version).matches()) {
                throw new IllegalArgumentException("A driver needs group:artifact coordinates and a version, not "
                    + coordinates + " " + version);
            }
            if (sha256 == null || !sha256.matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException("A driver's SHA-256 is 64 lower-case hex digits");
            }
        }

        /** Without a driver class. */
        public Driver(String coordinates, String version, String sha256, String licence, String licenceUrl,
                      boolean bundleable) {
            this(coordinates, version, sha256, licence, licenceUrl, bundleable, null);
        }

        public String group() {
            return coordinates.substring(0, coordinates.indexOf(':'));
        }

        public String artifact() {
            return coordinates.substring(coordinates.indexOf(':') + 1);
        }

        /** The jar's file name, as Maven Central names it: {@code ojdbc11-23.26.3.0.0.jar}. */
        public String fileName() {
            return artifact() + "-" + version + ".jar";
        }

        /** The jar's path in a Maven repository: {@code com/oracle/database/jdbc/ojdbc11/23.26.3.0.0/ojdbc11-23.26.3.0.0.jar}. */
        public String repositoryPath() {
            return group().replace('.', '/') + "/" + artifact() + "/" + version + "/" + fileName();
        }
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

    /**
     * How much of an asset to read. {@code rowLimit} 0 reads everything. {@code columns}, when not
     * empty, are the only columns the read needs, in the asset's own names; {@code filter}, when not
     * null, narrows the read to rows that may meet the read node's condition. A connector that cannot
     * do either reads everything, and the read node narrows what arrives (see {@link #narrowsReads}).
     */
    record ReadOptions(String assetUrn, long rowLimit, int batchSize, List<String> columns, ReadFilter filter) {

        public ReadOptions {
            columns = columns == null ? List.of() : List.copyOf(columns);
        }

        /** Every column and row. */
        public ReadOptions(String assetUrn, long rowLimit, int batchSize) {
            this(assetUrn, rowLimit, batchSize, List.of(), null);
        }
    }

    /**
     * Whether {@link #reader} reads only {@link ReadOptions#columns()} and applies
     * {@link ReadOptions#filter()} at the source for this asset. The read node narrows what arrives
     * either way; this says only whether the source does the work.
     */
    default boolean narrowsReads(SourceConfig source, AssetInfo asset) {
        return false;
    }

    /**
     * Whether the source compares a column of this kind and source type as Kilnmark reads it, so a
     * {@link ReadFilter} may name it. A column read as text but stored otherwise (JSON, spatial, uuid)
     * or read as true/false but stored as a number is not: the source would compare something else.
     */
    default boolean comparesAsRead(String kind, String sourceType) {
        return true;
    }

    /**
     * A node that writes the rows it receives on its {@code in} port to {@code source}
     * (docs/design/pipelines.md, section 4). Connectors that can write list
     * {@link Capability#WRITE} in {@link #capabilities()} and override this; the default refuses.
     * The caller (the pipeline service) has already checked that the source allows writing and
     * that its hosts are allowed.
     *
     * @param write what to write and how; see {@link WriteOptions}
     * @throws ConnectorException if this connector cannot write, or the options are wrong for it
     */
    default io.kilnmark.kiln.Node writer(SourceConfig source, WriteOptions write) throws ConnectorException {
        throw new ConnectorException(displayName() + " sources cannot be written to yet");
    }

    /**
     * The further settings a {@code write} node to this kind of source accepts (the keys of
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
     * with the setting it is about. The default asks {@link #writer} for the node and reports its
     * refusal; connectors whose {@link #writer} connects override it.
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
     * Where and how a pipeline's {@code write} node writes.
     *
     * @param target    a table ({@code schema.table}) for databases, or a path inside the source for
     *                  files and object storage
     * @param mode      {@code create}, {@code append}, {@code replace} or {@code upsert}
     * @param key       the key columns for {@code upsert}; empty otherwise
     * @param format    for files: {@code csv} or {@code parquet}; null for databases
     * @param options   any further node settings from the pipeline YAML, as written; only the keys in
     *                  {@link Connector#writeOptions()} are read
     * @param assetUrn  the URN the written asset is recorded under, for metadata and lineage
     * @param profiling how to profile what is written, set by the server from the source's own
     *                  profiling settings: the settings a scan gives the profile node
     *                  ({@code keep-values}, {@code examples}, {@code sample-size},
     *                  {@code sample-mode}, {@code sample-key}, {@code sample-seed},
     *                  {@code sample-max-bytes}, {@code sketch-fields}); never from the YAML.
     *                  Empty: the profile node's defaults
     */
    record WriteOptions(String target, String mode, java.util.List<String> key, String format,
                        Map<String, Object> options, String assetUrn, Map<String, String> profiling) {
        public WriteOptions {
            key = key == null ? java.util.List.of() : java.util.List.copyOf(key);
            options = options == null ? Map.of() : Map.copyOf(options);
            profiling = profiling == null ? Map.of() : Map.copyOf(profiling);
        }

        /** Without profiling settings: the profile node's defaults apply. */
        public WriteOptions(String target, String mode, java.util.List<String> key, String format,
                            Map<String, Object> options, String assetUrn) {
            this(target, mode, key, format, options, assetUrn, Map.of());
        }
    }

    /**
     * One setting a source of this kind takes, as the connection form shows it and a save checks it.
     * Values are stored as text whatever the kind ({@code "true"}, {@code "5000"}); the kind says
     * which text is acceptable and which control a form uses.
     *
     * @param key          the stored key
     * @param label        what people read
     * @param required     whether a source cannot be saved without it
     * @param hint         one sentence on what it does
     * @param multiple     whether it holds several values, separated by commas (each checked by kind)
     * @param kind         one of {@link #KINDS}: {@code TEXT}, {@code NUMBER}, {@code BOOLEAN},
     *                     {@code CHOICE} or {@code URL}; {@code TEXT} when null. There is no secret
     *                     kind: settings are stored and shown as plain text, so a password or key
     *                     goes in the source's secret, which is encrypted
     * @param choices      the allowed values for {@code CHOICE}, matched ignoring case; else empty
     * @param defaultValue the value used when it is left empty, as stored text, or null when there is
     *                     none (or it depends on other settings)
     */
    record Setting(String key, String label, boolean required, String hint, boolean multiple,
                   io.kilnmark.kiln.SettingSpec.Kind kind, List<String> choices, String defaultValue) {

        /** The kinds a connector setting may have: the node kinds that fit a stored text value, and URL. */
        public static final Set<io.kilnmark.kiln.SettingSpec.Kind> KINDS = Set.of(io.kilnmark.kiln.SettingSpec.Kind.TEXT,
            io.kilnmark.kiln.SettingSpec.Kind.NUMBER, io.kilnmark.kiln.SettingSpec.Kind.BOOLEAN,
            io.kilnmark.kiln.SettingSpec.Kind.CHOICE, io.kilnmark.kiln.SettingSpec.Kind.URL);

        public Setting {
            java.util.Objects.requireNonNull(key, "key");
            java.util.Objects.requireNonNull(label, "label");
            hint = hint == null ? "" : hint;
            kind = kind == null ? io.kilnmark.kiln.SettingSpec.Kind.TEXT : kind;
            choices = choices == null ? List.of() : List.copyOf(choices);
            if (!KINDS.contains(kind)) {
                throw new IllegalArgumentException(key + ": a connector setting cannot be of kind " + kind);
            }
            if ((kind == io.kilnmark.kiln.SettingSpec.Kind.CHOICE) == choices.isEmpty()) {
                throw new IllegalArgumentException(key + ": choices go with CHOICE, and CHOICE needs them");
            }
            if (defaultValue != null) {
                String wrong = wrong(kind, choices, defaultValue);
                if (wrong != null) {
                    throw new IllegalArgumentException(key + ": the default must be " + wrong);
                }
            }
        }

        /** Free text with no default: the setting as connectors declared it before settings had kinds. */
        public Setting(String key, String label, boolean required, String hint, boolean multiple) {
            this(key, label, required, hint, multiple, io.kilnmark.kiln.SettingSpec.Kind.TEXT, List.of(), null);
        }

        /** Free text. */
        public static Setting text(String key, String label, boolean required, String hint) {
            return new Setting(key, label, required, hint, false);
        }

        /** An address: a JDBC URL, an endpoint. */
        public static Setting url(String key, String label, boolean required, String hint) {
            return new Setting(key, label, required, hint, false, io.kilnmark.kiln.SettingSpec.Kind.URL, List.of(),
                null);
        }

        /** An optional number, with its default (or null). */
        public static Setting number(String key, String label, String defaultValue, String hint) {
            return new Setting(key, label, false, hint, false, io.kilnmark.kiln.SettingSpec.Kind.NUMBER, List.of(),
                defaultValue);
        }

        /** An optional on/off setting, stored as {@code true} or {@code false}. */
        public static Setting bool(String key, String label, boolean defaultValue, String hint) {
            return new Setting(key, label, false, hint, false, io.kilnmark.kiln.SettingSpec.Kind.BOOLEAN, List.of(),
                Boolean.toString(defaultValue));
        }

        /** An optional choice of {@code choices}, with its default (or null). */
        public static Setting choice(String key, String label, List<String> choices, String defaultValue,
                                     String hint) {
            return new Setting(key, label, false, hint, false, io.kilnmark.kiln.SettingSpec.Kind.CHOICE, choices,
                defaultValue);
        }

        /**
         * The problem with a value for this setting, starting with its key, or null when its kind
         * accepts it. Null and blank values are left to {@code required} and the connector's own checks.
         */
        public String problem(String value) {
            if (value == null || value.isBlank()) {
                return null;
            }
            List<String> values = multiple ? java.util.Arrays.stream(value.split(",")).map(String::strip)
                .filter(v -> !v.isEmpty()).toList() : List.of(value);
            for (String v : values) {
                String wrong = wrong(kind, choices, v);
                if (wrong != null) {
                    return key + ": " + wrong + ", not '" + v + "'";
                }
            }
            return null;
        }

        /** The kind problems of every setting of {@code settings} that {@code source} gives a value. */
        public static List<String> problems(List<Setting> settings, SourceConfig source) {
            List<String> problems = new java.util.ArrayList<>();
            for (Setting s : settings) {
                String p = s.problem(source.setting(s.key()));
                if (p != null) {
                    problems.add(p);
                }
            }
            return problems;
        }

        /** What a value of {@code kind} must be, when {@code value} is not one; null when it is. */
        private static String wrong(io.kilnmark.kiln.SettingSpec.Kind kind, List<String> choices, String value) {
            String v = value.strip();
            return switch (kind) {
                case BOOLEAN -> v.equalsIgnoreCase("true") || v.equalsIgnoreCase("false") ? null : "true or false";
                case NUMBER -> isNumber(v) ? null : "a number";
                case CHOICE -> choices.stream().anyMatch(c -> c.equalsIgnoreCase(v)) ? null
                    : "one of " + String.join(", ", choices);
                case URL -> URL_SHAPE.matcher(v).matches() ? null : "an address such as "
                    + "https://storage.example.com or jdbc:postgresql://db.example.com/sales, with no spaces";
                default -> null;
            };
        }

        private static final java.util.regex.Pattern URL_SHAPE =
            java.util.regex.Pattern.compile("[A-Za-z][A-Za-z0-9+.-]*:\\S+");

        private static boolean isNumber(String v) {
            if (v.isEmpty() || !v.chars().allMatch(c -> Character.isDigit(c) || c == '.' || c == '-')) {
                return false;
            }
            try {
                new java.math.BigDecimal(v);
                return true;
            } catch (NumberFormatException e) {
                return false;
            }
        }
    }
}

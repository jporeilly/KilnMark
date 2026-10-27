package io.kilnmark.server.pipelines;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import io.kilnmark.graph.store.Records;
import io.kilnmark.kiln.SettingSpec;
import io.kilnmark.kiln.Step;
import io.kilnmark.kiln.StepException;
import io.kilnmark.kiln.StepFactory;
import io.kilnmark.kiln.StepKind;
import io.kilnmark.kiln.connect.Connector;
import io.kilnmark.kiln.connect.ConnectorException;
import io.kilnmark.server.auth.AccessRole;
import io.kilnmark.server.sources.CommonSettings;

/**
 * The {@code write} step: rows into a table or file of a saved source, through its connector's
 * writer (docs/design/pipelines.md section 4). Sources are read-only unless an admin has set their
 * {@code allow_writing}; that, the role of whoever asked for the run (steward or admin), and the
 * allowed hosts are checked when the run starts, as well as when the pipeline is validated.
 *
 * <p>Where to write is {@code target}; {@code table} (databases) and {@code path} (files) are other
 * names for it, as the design's examples use them.
 */
public final class WriteStepFactory implements StepFactory {

    public static final String TYPE = "write";
    public static final List<String> MODES = List.of("create", "append", "replace", "upsert");
    public static final List<String> FORMATS = List.of("csv", "parquet");
    private static final List<String> TARGETS = List.of("target", "table", "path");
    /**
     * Names that say where or as whom a write connects, whatever the connector: the pipeline names a
     * saved source, and only the source (which an admin allowed) says that. A writer's declared
     * options with these names are never offered as step settings. ({@code profile} is an AWS
     * credentials profile: profiling is {@code profile_output}.)
     */
    static final java.util.Set<String> LOCATION_KEYS = java.util.Set.of("url", "jdbc_url", "endpoint", "bucket", "path",
        "root", "roots", "folder", "host", "hosts", "port", "database", "db", "schema", "schemas", "catalog", "prefix",
        "region", "user", "username", "password", "secret", "token", "access_key_id", "secret_access_key",
        "session_token", "credentials", "profile", "account");

    private final PipelineSources sources;

    public WriteStepFactory(PipelineSources sources) {
        this.sources = sources;
    }

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public StepKind kind() {
        return StepKind.SINK;
    }

    @Override
    public String label() {
        return "Write to a table or file";
    }

    @Override
    public String help() {
        return "Writes rows to a database table or a file in a saved source. The source must allow writing, which an "
            + "admin sets; what is written is registered in the catalogue with its lineage.";
    }

    @Override
    public List<String> inputPorts() {
        return List.of("in");
    }

    @Override
    public List<String> outputPorts() {
        return List.of();
    }

    /** The step's own settings: where and how to write. */
    static final List<SettingSpec> BASE = List.of(
        SettingSpec.required("source", "Source", SettingSpec.Kind.SOURCE, "The saved source to write to"),
        SettingSpec.optional("target", "Table or file", SettingSpec.Kind.ASSET, null,
            "A table as schema.table, or a path inside the source for files"),
        SettingSpec.optional("table", "Table", SettingSpec.Kind.TEXT, null, "Another name for target, for a table"),
        SettingSpec.optional("path", "File path", SettingSpec.Kind.TEXT, null, "Another name for target, for a file"),
        SettingSpec.choice("mode", "Mode", false, "create", "create fails if it exists; append adds rows; replace "
            + "swaps in the new rows at the end; upsert updates rows with the same key and adds the rest", MODES),
        SettingSpec.optional("key", "Key columns", SettingSpec.Kind.COLUMNS, null,
            "The columns that identify a row, for upsert"),
        SettingSpec.choice("format", "File format", false, null,
            "For files: csv or parquet. Taken from the path's extension when left out", FORMATS));

    private static final java.util.Set<String> BASE_KEYS = java.util.Set.of("source", "target", "table", "path",
        "mode", "key", "format");

    @Override
    public List<SettingSpec> settings() {
        // The step's own settings, then every setting a connector's writer declares
        // (Connector.writeOptions()), never an address or credential name. Which of them a step may use
        // depends on its source's writer: see writerKeys.
        Map<String, SettingSpec> out = new LinkedHashMap<>();
        BASE.forEach(spec -> out.put(spec.key(), spec));
        for (Connector c : sources.connectors()) {
            for (String key : writerKeys(c)) {
                c.writeOptions().stream().filter(spec -> spec.key().equals(key)).findFirst()
                    .ifPresent(spec -> out.putIfAbsent(key, spec));
            }
        }
        return List.copyOf(out.values());
    }

    /**
     * For each connector kind that can be written to, the writer settings a step writing to a source
     * of that kind takes ({@link #writerKeys}), so the canvas offers only those for the chosen source.
     */
    public Map<String, List<String>> writerSettingsByKind() {
        Map<String, List<String>> out = new java.util.TreeMap<>();
        for (Connector c : sources.connectors()) {
            if (c.capabilities().contains(Connector.Capability.WRITE)) {
                out.put(c.kind(), List.copyOf(writerKeys(c)));
            }
        }
        return out;
    }

    /**
     * The writer settings a step writing to a source of this kind may use: exactly those its writer
     * declares in {@link Connector#writeOptions()}, less any name that says where or as whom to connect.
     * None for a connector that declares none.
     */
    static java.util.Set<String> writerKeys(Connector c) {
        java.util.Set<String> keys = new java.util.LinkedHashSet<>();
        if (c == null) {
            return keys;
        }
        for (SettingSpec spec : c.writeOptions()) {
            if (!LOCATION_KEYS.contains(spec.key()) && !c.addressKeys().contains(spec.key())
                && !BASE_KEYS.contains(spec.key())) {
                keys.add(spec.key());
            }
        }
        return keys;
    }

    @Override
    public List<String> check(Map<String, Object> config) {
        List<String> problems = new ArrayList<>();
        String name = PipelineSources.text(config, "source");
        if (name != null) {
            Optional<Records.Source> s = sources.source(name);
            if (s.isEmpty()) {
                problems.add("source: there is no saved source called '" + name + "'");
            } else {
                if (!CommonSettings.allowsWriting(s.get().settings())) {
                    problems.add("source: " + name + " is read-only. An admin can turn on Allow writing for it");
                }
                Optional<Connector> c = sources.connector(s.get().kind());
                if (c.isEmpty()) {
                    problems.add("source: this server has no connector for sources of kind '" + s.get().kind() + "'");
                } else if (!c.get().capabilities().contains(Connector.Capability.WRITE)) {
                    problems.add("source: " + c.get().displayName() + " sources cannot be written to yet");
                }
            }
        }
        List<String> given = TARGETS.stream().filter(k -> config.get(k) != null).toList();
        if (given.isEmpty()) {
            problems.add("target: say where to write: target (or table for a database, path for a file)");
        } else if (given.size() > 1) {
            problems.add(given.get(1) + ": give one of target, table or path, not " + String.join(" and ", given));
        } else {
            String t = PipelineSources.text(config, given.get(0));
            String problem = targetProblem(t);
            if (problem != null) {
                problems.add(given.get(0) + ": " + problem);
            }
        }
        String mode = mode(config);
        if (mode == null) {
            problems.add("mode: one of " + String.join(", ", MODES));
        }
        List<String> key = key(config);
        if (key == null) {
            problems.add("key: a column name or a list of them, such as [customer_id]");
        } else if ("upsert".equals(mode) && key.isEmpty()) {
            problems.add("key: upsert needs the key columns that identify a row");
        }
        Object format = config.get("format");
        if (format != null && (!(format instanceof String f) || !FORMATS.contains(f.strip().toLowerCase(Locale.ROOT)))) {
            problems.add("format: csv or parquet");
        }
        Connector connector = name == null ? null
            : sources.source(name).flatMap(s -> sources.connector(s.kind())).orElse(null);
        String refused = notTaken(config, connector);
        if (refused != null) {
            problems.add(refused + ": " + (connector == null ? "this" : connector.displayName())
                + " sources' writer does not take this setting");
        } else if (connector != null) {
            problems.addAll(kindProblems(config, connector));
        }
        return problems;
    }

    /**
     * Writer settings whose value is not of the kind the source's writer declares (a number, true or
     * false, one of its choices, a single value), so {@code commit_every: lots} is caught when the
     * pipeline is checked rather than when it runs.
     */
    static List<String> kindProblems(Map<String, Object> config, Connector connector) {
        List<String> problems = new ArrayList<>();
        for (SettingSpec spec : connector.writeOptions()) {
            Object v = config.get(spec.key());
            if (v == null || BASE_KEYS.contains(spec.key())) {
                continue;
            }
            String wrong = switch (spec.kind()) {
                case NUMBER -> v instanceof Number ? null : "a number";
                case BOOLEAN -> v instanceof Boolean ? null : "true or false";
                case CHOICE -> v instanceof String c && spec.choices().contains(c.strip()) ? null
                    : "one of " + String.join(", ", spec.choices());
                case TEXT, EXPRESSION, COLUMN, SOURCE, ASSET -> v instanceof String || v instanceof Number
                    || v instanceof Boolean ? null : "a single value, not a list or mapping";
                default -> null;
            };
            if (wrong != null) {
                problems.add(spec.key() + ": " + wrong + ", not " + (v instanceof String ? "'" + v + "'"
                    : v instanceof Map<?, ?> ? "a mapping" : v instanceof List<?> ? "a list" : String.valueOf(v)));
            }
        }
        return problems;
    }

    /** The first writer setting given that the source's writer does not take (allow-list), or null. */
    static String notTaken(Map<String, Object> config, Connector connector) {
        java.util.Set<String> allowed = writerKeys(connector);
        for (Map.Entry<String, Object> e : config.entrySet()) {
            if (e.getValue() != null && !BASE_KEYS.contains(e.getKey()) && !allowed.contains(e.getKey())) {
                return e.getKey();
            }
        }
        return null;
    }

    /** Why a target is not acceptable, or null: it must stay inside the source. */
    static String targetProblem(String target) {
        if (target == null) {
            return "a table such as billing.customer_spend, or a path such as exports/spend.csv";
        }
        if (target.startsWith("/") || target.startsWith("\\") || target.matches("^[A-Za-z]:.*")) {
            return "a path inside the source, without a leading / or drive";
        }
        for (String part : target.split("[/\\\\]")) {
            if (part.equals("..")) {
                return "a path inside the source: .. is not allowed";
            }
        }
        if (target.chars().anyMatch(ch -> ch < 0x20)) {
            return "control characters are not allowed";
        }
        return null;
    }

    /** Where the step writes, whichever of target, table and path names it; null if none does. */
    public static String target(Map<String, Object> config) {
        for (String k : TARGETS) {
            String t = PipelineSources.text(config, k);
            if (t != null) {
                return t.startsWith("./") ? t.substring(2) : t;
            }
        }
        return null;
    }

    /** The mode, {@code create} when left out; null if it is not one of {@link #MODES}. */
    public static String mode(Map<String, Object> config) {
        Object v = config.get("mode");
        if (v == null) {
            return "create";
        }
        String m = v instanceof String s ? s.strip().toLowerCase(Locale.ROOT) : null;
        return m != null && MODES.contains(m) ? m : null;
    }

    /** The key columns (empty when none); null when the setting is not a name or list of names. */
    public static List<String> key(Map<String, Object> config) {
        Object v = config.get("key");
        if (v == null) {
            return List.of();
        }
        if (v instanceof String s) {
            return s.isBlank() ? List.of() : List.of(s.strip());
        }
        if (v instanceof List<?> list) {
            List<String> out = new ArrayList<>();
            for (Object o : list) {
                if (!(o instanceof String s) || s.isBlank()) {
                    return null;
                }
                out.add(s.strip());
            }
            return out;
        }
        return null;
    }

    /** The URN the written asset is recorded under, as a scan would record it; null if the settings do not say. */
    public static String urn(Map<String, Object> config) {
        return PipelineSources.urn(PipelineSources.text(config, "source"), target(config));
    }

    @Override
    public Step create(Map<String, Object> config) throws StepException {
        RunContext run = RunContext.current().orElseThrow(() -> new StepException(
            "A write step is set up only when its pipeline runs"));
        if (run.role() == null || run.role() == AccessRole.VIEWER) {
            throw new StepException("Only stewards and admins can run pipelines that write; this run was asked for "
                + "by a viewer");
        }
        String source = PipelineSources.text(config, "source");
        String target = target(config);
        if (source == null || target == null || targetProblem(target) != null) {
            throw new StepException("A write step needs a source and a table or path inside it");
        }
        String step = "write " + target;
        PipelineSources.Resolved r = sources.resolve(step, source, run);
        if (!CommonSettings.allowsWriting(r.source().settings())) {
            throw new StepException("Step " + step + ": source " + source + " is read-only. An admin can turn on "
                + "Allow writing for it");
        }
        String refused = notTaken(config, r.connector());
        if (refused != null) {
            throw new StepException("Step " + step + ": " + r.connector().displayName() + " sources' writer does not "
                + "take the setting '" + refused + "'");
        }
        String mode = mode(config);
        List<String> key = key(config);
        if (mode == null || key == null) {
            throw new StepException("Step " + step + " has settings that need fixing; validate the pipeline");
        }
        Object f = config.get("format");
        String format = f instanceof String s ? s.strip().toLowerCase(Locale.ROOT) : null;
        // The writer's settings as written (the connector's options take no nulls).
        Map<String, Object> options = new LinkedHashMap<>();
        config.forEach((k, v) -> {
            if (v != null && !BASE_KEYS.contains(k)) {
                options.put(k, v);
            }
        });
        // The written asset's URN as a scan of the source records it (for a prefixed S3 source, prefix +
        // path), then the source's own profiling settings for that asset: writers profile what they
        // write, as a scan would (when the source keeps no values, keep-values is false).
        String urn;
        try {
            urn = PipelineSources.urn(source, r.connector().writtenAssetPath(r.config(),
                new Connector.WriteOptions(target, mode, key, format, options, PipelineSources.urn(source, target))));
        } catch (ConnectorException | RuntimeException e) {
            throw new StepException("Cannot write " + target + " to " + source + ": " + run.scrub(String.valueOf(
                e.getMessage())));
        }
        if (urn == null) {
            urn = PipelineSources.urn(source, target);
        }
        Map<String, String> profiling = sources.profileSettings(r.source(), r.connector(), target, key, urn,
            run.writeColumns(source, target));
        Connector.WriteOptions write = new Connector.WriteOptions(target, mode, key, format, options, urn, profiling);
        run.wrote(source, target, write.assetUrn());
        try {
            return r.connector().writer(r.config(), write);
        } catch (ConnectorException e) {
            throw new StepException("Cannot write " + target + " to " + source + ": " + run.scrub(e.getMessage()));
        } catch (RuntimeException e) {
            throw new StepException("Cannot write " + target + " to " + source + ": " + run.scrub(String.valueOf(
                e.getMessage())));
        }
    }
}

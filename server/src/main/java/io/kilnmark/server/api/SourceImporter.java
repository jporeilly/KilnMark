package io.kilnmark.server.api;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.kilnmark.graph.store.GraphStore;
import io.kilnmark.graph.store.Records;
import io.kilnmark.kiln.connect.AssetFilter;
import io.kilnmark.kiln.connect.Connector;
import io.kilnmark.kiln.connect.Connectors;
import io.kilnmark.server.KilnmarkConfig;
import io.kilnmark.server.SecretBox;
import io.kilnmark.server.sources.CommonSettings;
import io.kilnmark.server.sources.HostRemap;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * Bulk registration of data sources from a CSV or JSON file, re-runnable: each row creates the
 * source it names, or updates the source of that name to match the row, or leaves it unchanged.
 * Every row is checked first. By default, if any row is wrong nothing changes and the report says
 * what to fix on which line; with {@code onError=skip} the right rows are applied anyway. A dry run
 * changes nothing.
 *
 * <p>CSV (header row required, any column order): {@code name}, optionally {@code kind} (default
 * {@code postgresql}, or the existing source's kind), {@code password} or {@code password_ref}, and
 * one column per setting: the connector's (for example {@code url}, {@code username},
 * {@code schemas}; lists separated by semicolons) and the common ones ({@code description},
 * {@code ai_egress}, {@code include}, {@code exclude}). Database kinds may give {@code host},
 * {@code port} and {@code database} instead of {@code url}. JSON: an array of objects with the same
 * keys, or with the settings in a {@code settings} object. {@code display_name} sets the name people
 * see; the {@code name} is the source's fixed key. {@code password_ref} keeps passwords
 * out of the file: {@code env:VARIABLE} reads the server's environment, {@code secret:NAME} reads
 * a file in the configured secrets folder.
 *
 * <p>An update changes only the settings the file has (its CSV columns, or a JSON object's keys);
 * an empty value removes that setting, and every other setting, such as a steward's later edits,
 * stays. {@code ai_egress} is never loosened by an empty value; a row that sets a looser one says
 * so in its notes. A row without a password keeps the stored one, unless it changes where the
 * password would go (the URL or user name), which needs the password again. A row with a password
 * always counts as updated: the stored one is never compared with it.
 */
@ApplicationScoped
public class SourceImporter {

    static final int MAX_ROWS = 1000;
    private static final Pattern ENV = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");
    private static final Pattern SECRET = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]*");
    private static final Pattern DATABASE = Pattern.compile("[A-Za-z0-9_$.-]{1,128}");

    /**
     * One row of the file. {@code present} names the settings the file has for it (its CSV columns
     * or JSON keys, plus {@code display_name} if given), even where a value is empty: an update
     * changes only those.
     */
    public record Row(int line, String name, String kind, Map<String, String> settings, String password,
                      String passwordRef, String host, String port, String database, String displayName,
                      Set<String> present) {

        public Row {
            present = Set.copyOf(present == null ? settings.keySet() : present);
        }

        public Row(int line, String name, String kind, Map<String, String> settings, String password,
                   String passwordRef) {
            this(line, name, kind, settings, password, passwordRef, null, null, null, null, null);
        }
    }

    private static final Set<String> RESERVED = Set.of("name", "kind", "password", "password_ref", "passwordref",
        "settings", "host", "port", "database", "display_name", "displayname");

    /**
     * How to run an import. {@code skipErrors}: apply the right rows even if others are wrong.
     * {@code remapHosts}: rewrite host names with the server's host map. {@code test} and
     * {@code scan}: afterwards, test each source's connection and queue a scan of each (only of
     * those whose test passed, when testing), reported in a batch the UI polls.
     */
    public record Options(boolean dryRun, boolean skipErrors, boolean remapHosts, boolean test, boolean scan) {
    }

    /**
     * One row's outcome. {@code status}: created, updated, unchanged or failed (in a dry run, what
     * would happen). {@code notes} says what the import changed on its own, such as a remapped host.
     */
    public record RowResult(int line, String name, String status, String error, List<String> notes) {
    }

    /**
     * {@code applied} is false when nothing was changed: a dry run, or a row was wrong and errors
     * were not skipped. {@code errors} is {@code failed} under its earlier name. {@code batchId}
     * names the follow-up tests and scans, if any were asked for.
     */
    public record Report(boolean dryRun, boolean applied, int created, int updated, int unchanged, int failed,
                         int errors, List<RowResult> rows, Long batchId) {
    }

    /** A row checked and ready to apply. */
    private record Plan(RowResult result, Records.NewSource create, Long updateId, Records.SourceChange update,
                        SourceService.Draft draft) {
    }

    private final GraphStore store;
    private final SecretBox secrets;
    private final KilnmarkConfig config;
    private final ObjectMapper json;
    private final Connectors connectors;
    private final SourceService sources;
    private final ImportBatches batches;

    public SourceImporter(GraphStore store, SecretBox secrets, KilnmarkConfig config, ObjectMapper json,
                          Connectors connectors, SourceService sources, ImportBatches batches) {
        this.store = store;
        this.secrets = secrets;
        this.config = config;
        this.json = json;
        this.connectors = connectors;
        this.sources = sources;
        this.batches = batches;
    }

    /** The import as version 1 ran it: check every row, create all or nothing. */
    public Report run(List<Row> rows, boolean dryRun) {
        return run(rows, new Options(dryRun, false, false, false, false), "import");
    }

    public Report run(List<Row> rows, Options options, String who) {
        if (rows.isEmpty()) {
            throw new IllegalArgumentException("The file has no sources in it");
        }
        if (rows.size() > MAX_ROWS) {
            throw new IllegalArgumentException("At most " + MAX_ROWS + " sources per import; this file has " + rows.size());
        }
        HostRemap remap = options.remapHosts() ? hostMap() : null;
        Map<String, Records.Source> existing = new HashMap<>();
        store.sources().forEach(s -> existing.put(s.name(), s));
        Set<String> inFile = new HashSet<>();
        List<Plan> plans = new ArrayList<>();
        int failed = 0;
        for (Row r : rows) {
            Plan p;
            try {
                if (r.name() != null && !inFile.add(r.name())) {
                    throw new IllegalArgumentException("the name " + r.name() + " appears more than once in the file");
                }
                p = plan(r, existing.get(r.name()), remap);
            } catch (IllegalArgumentException e) {
                p = new Plan(new RowResult(r.line(), r.name(), "failed", e.getMessage(), List.of()), null, null, null,
                    null);
                failed++;
            }
            plans.add(p);
        }
        boolean apply = !options.dryRun() && (failed == 0 || options.skipErrors());
        Map<String, Long> ids = new HashMap<>();
        existing.values().forEach(s -> ids.put(s.name(), s.id()));
        if (apply) {
            List<Records.NewSource> creates = plans.stream().filter(p -> p.create() != null).map(Plan::create).toList();
            Map<Long, Records.SourceChange> updates = new LinkedHashMap<>();
            plans.stream().filter(p -> p.update() != null).forEach(p -> updates.put(p.updateId(), p.update()));
            if (!creates.isEmpty() || !updates.isEmpty()) {
                store.saveSources(creates, updates).forEach(s -> ids.put(s.name(), s.id()));
            }
        }
        int created = count(plans, "created");
        int updated = count(plans, "updated");
        int unchanged = count(plans, "unchanged");
        Long batch = null;
        boolean follow = (options.test() || options.scan() && !options.dryRun()) && (apply || options.dryRun()
            && failed < plans.size());
        if (follow) {
            List<ImportBatches.Item> items = plans.stream().filter(p -> p.draft() != null)
                .map(p -> new ImportBatches.Item(p.result().line(), p.result().name(), p.draft().connector(),
                    p.draft().config(), options.dryRun() ? null : ids.get(p.result().name()))).toList();
            if (!items.isEmpty()) {
                batch = batches.start(who, options.dryRun(), options.test(), options.scan(), items);
            }
        }
        return new Report(options.dryRun(), apply, apply ? created : 0, apply ? updated : 0, unchanged, failed, failed,
            plans.stream().map(Plan::result).toList(), batch);
    }

    private static int count(List<Plan> plans, String status) {
        return (int) plans.stream().filter(p -> p.result().status().equals(status)).count();
    }

    /** The server's host map, which an import may only use when an administrator has set one. */
    private HostRemap hostMap() {
        HostRemap map = HostRemap.parse(config.sources().hostMap().orElse(List.of()));
        if (map.isEmpty()) {
            throw new IllegalArgumentException("remapHosts: this server has no host map. An administrator sets one in "
                + HostRemap.SETTING + " (KILNMARK_SOURCES_HOST_MAP), for example localhost=host.docker.internal");
        }
        return map;
    }

    /**
     * Checks one row and works out what applying it does.
     *
     * @throws IllegalArgumentException with what to fix
     */
    private Plan plan(Row r, Records.Source existing, HostRemap remap) {
        if (r.name() == null || r.name().isBlank()) {
            throw new IllegalArgumentException("name: missing");
        }
        String rowKind = r.kind() == null || r.kind().isBlank() ? null : r.kind().trim();
        if (existing != null && rowKind != null && !rowKind.equals(existing.kind())) {
            throw new IllegalArgumentException("kind: a source called " + r.name() + " already exists, of kind "
                + existing.kind() + ". A source's kind cannot change: delete it first, or use another name");
        }
        String kind = rowKind != null ? rowKind : existing != null ? existing.kind() : "postgresql";
        Connector connector = sources.connector(kind);
        Map<String, String> row = new HashMap<>(r.settings());
        Set<String> present = new HashSet<>(r.present());
        List<String> notes = new ArrayList<>();
        address(r, connector, row, remap, notes, present);
        if (remap != null) {
            for (String key : connector.addressKeys()) { // the host map rewrites addresses only
                String v = row.get(key);
                String to = remap.apply(v);
                if (v != null && !to.equals(v)) {
                    notes.add(key + ": host changed for this server, to " + to);
                    row.put(key, to);
                }
            }
        }
        String password = password(r);
        if (existing == null) {
            SourceService.Draft d = sources.draft(new SourceForm(r.name(), rowKind, row, password, null, null,
                r.displayName()), null, true);
            RowResult res = new RowResult(r.line(), r.name(), "created", null, notes);
            return new Plan(res, new Records.NewSource(r.name(), kind, d.config().settings(),
                d.newSecret() == null ? null : secrets.seal(d.newSecret()), d.displayName()), null, null, d);
        }
        Map<String, String> merged = merge(existing.settings(), row, present, notes);
        String displayName = present.contains("display_name") ? r.displayName()
            : existing.displayName().equals(existing.name()) ? null : existing.displayName();
        SourceService.Draft d = sources.draft(new SourceForm(r.name(), rowKind, merged, password, null, null,
            displayName), existing, true);
        String shown = d.displayName() == null ? existing.name() : d.displayName();
        // A supplied password always counts as a change: comparing it with the stored one would
        // tell whoever runs imports whether they had guessed it.
        if (!d.replaceSecret() && existing.settings().equals(d.config().settings())
            && shown.equals(existing.displayName())) {
            return new Plan(new RowResult(r.line(), r.name(), "unchanged", null, notes), null, null, null, d);
        }
        Records.SourceChange change = new Records.SourceChange(d.displayName(), d.config().settings(),
            d.replaceSecret(), d.replaceSecret() && d.newSecret() != null ? secrets.seal(d.newSecret()) : null,
            d.keepSecretIf());
        return new Plan(new RowResult(r.line(), r.name(), "updated", null, notes), null, existing.id(), change, d);
    }

    /** How freely a source may reach AI models, from strictest (0) to most open. */
    private static int openness(CommonSettings.AiEgress e) {
        return switch (e) {
            case NONE -> 0;
            case LOCAL_ONLY -> 1;
            case ALLOWED -> 2;
        };
    }

    /**
     * The stored settings with the file's changes: only the keys the file has (its CSV columns or
     * JSON keys) change, a present but empty one is removed, and every other setting, such as a
     * steward's later edits, stays. {@code ai_egress} is never loosened unless the row says so: an
     * empty value keeps the stored one, and an explicit looser value is applied with a note.
     */
    static Map<String, String> merge(Map<String, String> stored, Map<String, String> row, Set<String> present,
                                     List<String> notes) {
        Map<String, String> merged = new HashMap<>(stored);
        for (String key : present) {
            String v = row.get(key);
            if (key.equals(CommonSettings.AI_EGRESS)) {
                if (v == null || v.isBlank()) {
                    continue; // never loosened by an empty cell
                }
                CommonSettings.AiEgress was = CommonSettings.AiEgress.of(stored);
                CommonSettings.AiEgress now = CommonSettings.AiEgress.parse(v);
                if (now != null && openness(now) > openness(was)) {
                    notes.add("ai_egress: loosened from " + was.id() + " to " + now.id() + ", as the file says");
                }
            }
            if (v == null || v.isBlank()) {
                merged.remove(key);
            } else {
                merged.put(key, v);
            }
        }
        return merged;
    }

    /** Builds the connector's address settings (a JDBC URL) from host, port and database columns. */
    private static void address(Row r, Connector connector, Map<String, String> settings, HostRemap remap,
                                List<String> notes, Set<String> present) {
        String host = blank(r.host());
        if (host == null) {
            if (blank(r.port()) != null || blank(r.database()) != null) {
                throw new IllegalArgumentException("host: give the host too, or a url instead of port and database");
            }
            return;
        }
        if (settings.containsKey("url")) {
            throw new IllegalArgumentException("url: give a url, or host, port and database, not both");
        }
        if (!HostRemap.validHost(host)) {
            throw new IllegalArgumentException("host: '" + host + "' is not a host name or address");
        }
        Integer port = null;
        if (blank(r.port()) != null) {
            try {
                port = Integer.parseInt(r.port().trim());
            } catch (NumberFormatException e) {
                port = -1;
            }
            if (port < 1 || port > 65535) {
                throw new IllegalArgumentException("port: a number from 1 to 65535, not '" + r.port().trim() + "'");
            }
        }
        String database = blank(r.database());
        if (database != null && !DATABASE.matcher(database).matches()) {
            throw new IllegalArgumentException("database: letters, digits and _ $ . - only, not '" + database + "'");
        }
        if (remap != null) {
            String to = remap.host(host);
            if (!to.equals(host)) {
                notes.add("host: changed for this server, from " + host + " to " + to);
                host = to;
            }
        }
        Map<String, String> address = connector.addressSettings(host, port, database);
        if (address.isEmpty()) {
            throw new IllegalArgumentException("host: sources of kind " + connector.kind() + " do not take host, port "
                + "and database; use their own settings");
        }
        settings.putAll(address);
        present.addAll(address.keySet());
    }

    private static String blank(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private String password(Row r) {
        boolean hasPassword = r.password() != null && !r.password().isEmpty();
        boolean hasRef = r.passwordRef() != null && !r.passwordRef().isBlank();
        if (hasPassword && hasRef) {
            throw new IllegalArgumentException("give a password or a password_ref, not both");
        }
        if (hasPassword) {
            return r.password();
        }
        if (!hasRef) {
            return null;
        }
        String ref = r.passwordRef().trim();
        if (ref.startsWith("env:")) {
            String name = ref.substring(4);
            if (!ENV.matcher(name).matches()) {
                throw new IllegalArgumentException("password_ref: " + ref + " is not a valid variable name");
            }
            String value = System.getenv(name);
            if (value == null) {
                throw new IllegalArgumentException("password_ref: the server has no environment variable " + name);
            }
            return value;
        }
        if (ref.startsWith("secret:")) {
            String name = ref.substring(7);
            if (!SECRET.matcher(name).matches() || name.contains("..")) {
                throw new IllegalArgumentException("password_ref: " + ref + " is not a valid secret name");
            }
            Path file = Path.of(config.secretsDir()).resolve(name);
            try {
                return Files.readString(file, StandardCharsets.UTF_8).strip();
            } catch (IOException e) {
                throw new IllegalArgumentException("password_ref: no secret called " + name + " in " + config.secretsDir());
            }
        }
        throw new IllegalArgumentException("password_ref: use env:VARIABLE or secret:NAME");
    }

    // ---------------------------------------------------------------- reading the file

    public List<Row> fromJson(String text) {
        JsonNode root;
        try {
            root = json.readTree(text);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalArgumentException("The file is not valid JSON: " + e.getOriginalMessage());
        }
        if (root == null || !root.isArray()) {
            throw new IllegalArgumentException("Send a JSON array of sources");
        }
        List<Row> rows = new ArrayList<>();
        int i = 0;
        for (JsonNode n : root) {
            i++;
            Map<String, String> settings = new HashMap<>();
            Set<String> present = new HashSet<>();
            n.path("settings").properties().forEach(e -> {
                present.add(e.getKey());
                put(settings, e.getKey(), e.getValue());
            });
            n.properties().forEach(e -> {
                if (!RESERVED.contains(e.getKey().toLowerCase(Locale.ROOT))) {
                    present.add(e.getKey());
                    put(settings, e.getKey(), e.getValue());
                }
            });
            if (n.has("display_name") || n.has("displayName")) {
                present.add("display_name");
            }
            rows.add(new Row(i, text(n, "name"), text(n, "kind"), SourceResource.clean(settings), text(n, "password"),
                n.hasNonNull("password_ref") ? text(n, "password_ref") : text(n, "passwordRef"), text(n, "host"),
                text(n, "port"), text(n, "database"),
                n.hasNonNull("display_name") ? text(n, "display_name") : text(n, "displayName"), present));
        }
        return rows;
    }

    private static void put(Map<String, String> settings, String key, JsonNode v) {
        if (v == null || v.isNull() || v.isObject()) {
            return;
        }
        if (v.isArray()) {
            List<String> items = new ArrayList<>();
            v.forEach(x -> items.add(x.asText().trim()));
            settings.put(key, String.join(",", items));
        } else {
            settings.put(key, v.asText());
        }
    }

    private static String text(JsonNode n, String key) {
        JsonNode v = n.get(key);
        return v == null || v.isNull() ? null : v.asText().trim();
    }

    public List<Row> fromCsv(String text) {
        List<List<String>> records = Csv.parse(text);
        if (records.isEmpty()) {
            throw new IllegalArgumentException("The file is empty");
        }
        Map<String, Integer> header = new HashMap<>();
        List<String> head = records.get(0);
        for (int i = 0; i < head.size(); i++) {
            header.put(head.get(i).trim().toLowerCase(Locale.ROOT).replace(' ', '_'), i);
        }
        if (!header.containsKey("name")) {
            throw new IllegalArgumentException("The header row needs a 'name' column");
        }
        Set<String> present = new HashSet<>();
        header.keySet().forEach(k -> {
            if (!RESERVED.contains(k) || k.equals("display_name")) {
                present.add(k);
            }
        });
        List<Row> rows = new ArrayList<>();
        for (int r = 1; r < records.size(); r++) {
            List<String> rec = records.get(r);
            if (rec.stream().allMatch(String::isBlank)) {
                continue;
            }
            Map<String, String> settings = new HashMap<>();
            for (var h : header.entrySet()) {
                if (!RESERVED.contains(h.getKey())) {
                    String v = cell(rec, header, h.getKey());
                    if (v != null) {
                        // include and exclude keep their semicolons: a pattern may hold {a,b}
                        boolean patterns = h.getKey().equals(AssetFilter.INCLUDE) || h.getKey().equals(AssetFilter.EXCLUDE);
                        settings.put(h.getKey(), patterns ? v : v.replace(';', ','));
                    }
                }
            }
            rows.add(new Row(r + 1, cell(rec, header, "name"), cell(rec, header, "kind"),
                SourceResource.clean(settings), cell(rec, header, "password"), cell(rec, header, "password_ref"),
                cell(rec, header, "host"), cell(rec, header, "port"), cell(rec, header, "database"),
                cell(rec, header, "display_name"), present));
        }
        return rows;
    }

    private static String cell(List<String> rec, Map<String, Integer> header, String key) {
        Integer i = header.get(key);
        if (i == null || i >= rec.size()) {
            return null;
        }
        String v = rec.get(i);
        return key.equals("password") ? v : v.trim();
    }

    /** RFC 4180 CSV: quoted fields may hold commas, quotes ("") and line breaks. */
    static final class Csv {
        private Csv() {
        }

        static List<List<String>> parse(String text) {
            List<List<String>> out = new ArrayList<>();
            List<String> record = new ArrayList<>();
            StringBuilder field = new StringBuilder();
            boolean quoted = false;
            String s = text.startsWith("﻿") ? text.substring(1) : text; // Excel's byte-order mark
            for (int i = 0; i < s.length(); i++) {
                char c = s.charAt(i);
                if (quoted) {
                    if (c == '"' && i + 1 < s.length() && s.charAt(i + 1) == '"') {
                        field.append('"');
                        i++;
                    } else if (c == '"') {
                        quoted = false;
                    } else {
                        field.append(c);
                    }
                } else if (c == '"') {
                    quoted = true;
                } else if (c == ',') {
                    record.add(field.toString());
                    field.setLength(0);
                } else if (c == '\n' || c == '\r') {
                    if (c == '\r' && i + 1 < s.length() && s.charAt(i + 1) == '\n') {
                        i++;
                    }
                    record.add(field.toString());
                    field.setLength(0);
                    out.add(record);
                    record = new ArrayList<>();
                } else {
                    field.append(c);
                }
            }
            if (quoted) {
                throw new IllegalArgumentException("The file ends inside a quoted value");
            }
            if (field.length() > 0 || !record.isEmpty()) {
                record.add(field.toString());
                out.add(record);
            }
            return out;
        }
    }
}

package io.kilnmark.server.api;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.kilnmark.graph.store.GraphStore;
import io.kilnmark.graph.store.Records;
import io.kilnmark.server.KilnmarkConfig;
import io.kilnmark.server.SecretBox;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * Bulk registration of data sources from a CSV or JSON file. Every row is checked first; if any
 * row is wrong nothing is created, and the report says what to fix on which line. A dry run
 * checks without creating anything.
 *
 * <p>CSV columns (header row required, any order): {@code name}, {@code url}, and optionally
 * {@code username}, {@code password}, {@code password_ref} and {@code schemas} (separated by
 * semicolons). JSON: an array of objects with the same keys ({@code schemas} as an array).
 * {@code password_ref} keeps passwords out of the file: {@code env:VARIABLE} reads the server's
 * environment, {@code secret:NAME} reads a file in the configured secrets folder.
 */
@ApplicationScoped
public class SourceImporter {

    static final int MAX_ROWS = 1000;
    private static final Pattern ENV = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");
    private static final Pattern SECRET = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]*");

    public record Row(int line, String name, String url, String username, String password, String passwordRef,
                      List<String> schemas) {
    }

    public record RowResult(int line, String name, String status, String error) {
    }

    public record Report(boolean dryRun, int created, int errors, List<RowResult> rows) {
    }

    private final GraphStore store;
    private final SecretBox secrets;
    private final KilnmarkConfig config;
    private final ObjectMapper json;

    public SourceImporter(GraphStore store, SecretBox secrets, KilnmarkConfig config, ObjectMapper json) {
        this.store = store;
        this.secrets = secrets;
        this.config = config;
        this.json = json;
    }

    public Report run(List<Row> rows, boolean dryRun) {
        if (rows.isEmpty()) {
            throw new IllegalArgumentException("The file has no sources in it");
        }
        if (rows.size() > MAX_ROWS) {
            throw new IllegalArgumentException("At most " + MAX_ROWS + " sources per import; this file has " + rows.size());
        }
        Set<String> existing = new HashSet<>();
        store.sources().forEach(s -> existing.add(s.name()));
        Set<String> inFile = new HashSet<>();
        List<RowResult> results = new ArrayList<>();
        List<Records.NewSource> ready = new ArrayList<>();
        int errors = 0;
        for (Row r : rows) {
            String error = SourceRules.check(r.name(), "postgresql", r.url());
            if (error == null && existing.contains(r.name())) {
                error = "a source called " + r.name() + " already exists";
            }
            if (error == null && !inFile.add(r.name())) {
                error = "the name " + r.name() + " appears more than once in the file";
            }
            String password = null;
            if (error == null) {
                try {
                    password = password(r);
                } catch (IllegalArgumentException e) {
                    error = e.getMessage();
                }
            }
            if (error != null) {
                errors++;
                results.add(new RowResult(r.line(), r.name(), "error", error));
                continue;
            }
            results.add(new RowResult(r.line(), r.name(), dryRun ? "ok" : "created", null));
            if (!dryRun) {
                ready.add(new Records.NewSource(r.name(), "postgresql", r.url(), blank(r.username()),
                    password == null ? null : secrets.seal(password), r.schemas()));
            }
        }
        if (errors > 0) {
            List<RowResult> notCreated = results.stream()
                .map(x -> x.status().equals("created") ? new RowResult(x.line(), x.name(), "ok", null) : x).toList();
            return new Report(dryRun, 0, errors, notCreated);
        }
        if (!dryRun) {
            store.addSources(ready);
        }
        return new Report(dryRun, dryRun ? 0 : ready.size(), 0, results);
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

    private static String blank(String s) {
        return s == null || s.isBlank() ? null : s.trim();
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
            List<String> schemas = new ArrayList<>();
            n.path("schemas").forEach(s -> schemas.add(s.asText().trim()));
            rows.add(new Row(i, text(n, "name"), text(n, "url"), text(n, "username"), text(n, "password"),
                n.hasNonNull("password_ref") ? text(n, "password_ref") : text(n, "passwordRef"),
                schemas.stream().filter(s -> !s.isEmpty()).toList()));
        }
        return rows;
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
        for (String required : List.of("name", "url")) {
            if (!header.containsKey(required)) {
                throw new IllegalArgumentException("The header row needs a '" + required + "' column");
            }
        }
        List<Row> rows = new ArrayList<>();
        for (int r = 1; r < records.size(); r++) {
            List<String> rec = records.get(r);
            if (rec.stream().allMatch(String::isBlank)) {
                continue;
            }
            String schemas = cell(rec, header, "schemas");
            rows.add(new Row(r + 1, cell(rec, header, "name"), cell(rec, header, "url"), cell(rec, header, "username"),
                cell(rec, header, "password"), cell(rec, header, "password_ref"),
                schemas == null ? List.of()
                    : List.of(schemas.split(";")).stream().map(String::trim).filter(s -> !s.isEmpty()).toList()));
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

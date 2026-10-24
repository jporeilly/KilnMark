package io.kilnmark.server.ai;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * A question turned into a search: the words to search for, plus kinds, sources and field names
 * to narrow it. A model proposes it; {@link #validate} keeps only what the catalogue supports and
 * says what it dropped, so a person sees exactly what ran.
 *
 * @param ignored what the model asked for that the search could not use, in plain words
 */
public record QueryPlan(String text, List<String> kinds, List<String> sources, List<String> fields,
                        List<String> ignored) {

    /** The JSON schema the model must answer with. */
    static final String SCHEMA = """
        {"type": "object",
         "properties": {
           "text": {"type": "string", "description": "Words to search for: the subject of the question, without filler words. Empty if the question only names filters."},
           "kinds": {"type": "array", "items": {"type": "string"}, "description": "Kinds of asset asked for, from the known kinds."},
           "sources": {"type": "array", "items": {"type": "string"}, "description": "Source names mentioned, from the known sources."},
           "fields": {"type": "array", "items": {"type": "string"}, "description": "Column or field names the assets must have."},
           "tags": {"type": "array", "items": {"type": "string"}, "description": "Tags or classifications mentioned."}
         },
         "required": ["text", "kinds", "sources", "fields", "tags"],
         "additionalProperties": false}""";

    static final int MAX_TEXT = 200;
    static final int MAX_ITEMS = 5;
    private static final Pattern FIELD = Pattern.compile("[A-Za-z_][A-Za-z0-9_ .\\-]{0,62}");

    /** A model's answer as a JSON object, from text that may wrap it in a code fence or prose. */
    static Optional<JsonNode> parse(ObjectMapper json, String answer) {
        if (answer == null) {
            return Optional.empty();
        }
        int start = answer.indexOf('{');
        int end = answer.lastIndexOf('}');
        if (start < 0 || end <= start) {
            return Optional.empty();
        }
        try {
            JsonNode n = json.readTree(answer.substring(start, end + 1));
            return n != null && n.isObject() ? Optional.of(n) : Optional.empty();
        } catch (java.io.IOException e) {
            return Optional.empty();
        }
    }

    /**
     * Keeps what the catalogue supports. Kinds and sources must be ones that exist; field names
     * must look like names; tags are not in the catalogue yet, so they are dropped with a note.
     * Without words or filters, the question itself is searched.
     */
    static QueryPlan validate(JsonNode raw, String question, Collection<String> knownKinds,
                              Collection<String> knownSources) {
        List<String> ignored = new ArrayList<>();
        String text = raw.path("text").isTextual() ? clean(raw.path("text").asText()) : "";
        if (text.length() > MAX_TEXT) {
            text = text.substring(0, MAX_TEXT).strip();
        }
        Set<String> kinds = new LinkedHashSet<>();
        for (String k : strings(raw.path("kinds"))) {
            String match = matchKind(k, knownKinds);
            if (match != null) {
                kinds.add(match);
            } else {
                ignored.add("kind \"" + k + "\" (no assets of that kind)");
            }
        }
        Set<String> sources = new LinkedHashSet<>();
        for (String s : strings(raw.path("sources"))) {
            String match = knownSources.stream().filter(n -> n.equalsIgnoreCase(s.strip())).findFirst().orElse(null);
            if (match != null) {
                sources.add(match);
            } else {
                ignored.add("source \"" + s + "\" (no source by that name)");
            }
        }
        Set<String> fields = new LinkedHashSet<>();
        for (String f : strings(raw.path("fields"))) {
            String t = f.strip();
            if (FIELD.matcher(t).matches()) {
                fields.add(t.toLowerCase(Locale.ROOT));
            } else {
                ignored.add("field \"" + f + "\" (not a field name)");
            }
        }
        for (String t : strings(raw.path("tags"))) {
            ignored.add("tag \"" + t + "\" (tags are not in the catalogue yet)");
        }
        List<String> k = cap(kinds, "kinds", ignored);
        List<String> s = cap(sources, "sources", ignored);
        List<String> fl = cap(fields, "fields", ignored);
        if (text.isEmpty() && k.isEmpty() && s.isEmpty() && fl.isEmpty()) {
            text = clean(question);
            if (text.length() > MAX_TEXT) {
                text = text.substring(0, MAX_TEXT).strip();
            }
        }
        return new QueryPlan(text, k, s, fl, ignored);
    }

    private static List<String> cap(Set<String> values, String what, List<String> ignored) {
        List<String> all = new ArrayList<>(values);
        if (all.size() <= MAX_ITEMS) {
            return all;
        }
        ignored.add((all.size() - MAX_ITEMS) + " more " + what + " (at most " + MAX_ITEMS + " are used)");
        return all.subList(0, MAX_ITEMS);
    }

    /** The known kind a word names: exact, or the singular of a plural ("tables" is "table"). */
    static String matchKind(String word, Collection<String> known) {
        String w = word.strip().toLowerCase(Locale.ROOT);
        for (String candidate : List.of(w, w.endsWith("s") ? w.substring(0, w.length() - 1) : w)) {
            for (String k : known) {
                if (k.equalsIgnoreCase(candidate)) {
                    return k.toLowerCase(Locale.ROOT);
                }
            }
        }
        return null;
    }

    private static List<String> strings(JsonNode n) {
        List<String> out = new ArrayList<>();
        if (n.isArray()) {
            for (JsonNode e : n) {
                if (e.isTextual() && !e.asText().isBlank()) {
                    out.add(clean(e.asText()));
                }
                if (out.size() >= 20) {
                    break;
                }
            }
        } else if (n.isTextual() && !n.asText().isBlank()) {
            out.add(clean(n.asText()));
        }
        return out;
    }

    private static String clean(String s) {
        return s == null ? "" : s.replaceAll("\\p{Cntrl}", " ").replaceAll("\\s+", " ").strip();
    }

    HybridSearch.Filters filters() {
        return new HybridSearch.Filters(kinds, sources, fields);
    }
}

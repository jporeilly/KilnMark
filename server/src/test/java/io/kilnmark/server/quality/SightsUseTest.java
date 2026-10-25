package io.kilnmark.server.quality;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

/**
 * Masking is decided in one place: every resource and MCP tool method that returns values from the
 * data (profiles, samples, failing rows and queries, error text, evidence, drift, results, previews,
 * document text and snippets, exports) goes through the request's {@link Sights}, and none works out
 * an asset's marks or a {@link Masking.Sight} itself. A check over the source tree, method by method,
 * so a new method in an existing file that forgets fails the build.
 */
class SightsUseTest {

    private static final Path MAIN = Path.of("src/main/java/io/kilnmark/server");
    /** What reads values from the data, at the API boundary. */
    static final Pattern VALUES = Pattern.compile("profileJson\\(\\)|failingRows\\(\\)|failingQuery\\(\\)"
        + "|errorDetail\\(\\)|store\\.sample\\(|store\\.drift\\(|store\\.results\\(|store\\.measures\\("
        + "|quality\\.preview\\(|connector\\.preview\\(|texts\\.page|documents\\.chunks\\(|Masking\\.profile\\("
        + "|hybrid\\.search\\(|ask\\.ask\\(|StandardFormats\\.(odcs|openLineage)\\(");
    /** Working masking out apart from {@link Sights}. */
    static final Pattern OWN_MASKING = Pattern.compile("(store|quality)\\.sensitive\\("
        + "|new (io\\.kilnmark\\.server\\.quality\\.)?Masking\\.Sight\\(");
    /** What shows a method decides through Sights: the bean, or a sight it was handed. */
    private static final Pattern ASKS = Pattern.compile("\\bsights\\b|Masking\\.Sight\\b");
    /** Helpers behind an endpoint that checks: records, and document text read for DocumentResource. */
    private static final Set<String> HELPERS = Set.of("Views.java", "BrowseViews.java", "DocumentTexts.java");
    /** Methods that hand values to a helper which asks Sights, with that helper (checked to ask too). */
    private static final Map<String, String> DELEGATES = Map.of(
        "QualityResource.openLineage", "QualityResource.export",
        "QualityResource.odcs", "QualityResource.export",
        "DocumentResource.text", "DocumentResource.readable",
        "DocumentResource.find", "DocumentResource.readable",
        "DocumentResource.chunks", "DocumentResource.readable",
        "SourceService.preview", "SourceService.masked");
    private static final Set<String> KEYWORDS = Set.of("if", "for", "while", "switch", "catch", "synchronized",
        "return", "new", "else", "try");
    private static final Pattern HEADER = Pattern.compile(
        "(?m)^    (?:(?:public|protected|private|static|final|synchronized)\\s+)*[\\w<>\\[\\],.? ]+?\\s+(\\w+)\\s*\\(");

    /** The source without comments, strings, text blocks and characters, so braces count true. */
    static String code(String text) {
        String s = text.replaceAll("(?s)\"\"\".*?\"\"\"", "\"\"");
        s = s.replaceAll("(?s)/\\*.*?\\*/", " ");
        s = s.replaceAll("//[^\n]*", " ");
        s = s.replaceAll("\"(?:\\\\.|[^\"\\\\])*\"", "\"\"");
        return s.replaceAll("'(?:\\\\.|[^'\\\\])'", "' '");
    }

    /** Each method's body by name ({@code File.method}; overloads joined). */
    static Map<String, String> methods(String file, String text) {
        String s = code(text);
        Map<String, String> out = new LinkedHashMap<>();
        Matcher m = HEADER.matcher(s);
        while (m.find()) {
            int open = s.indexOf('{', m.end());
            int semi = s.indexOf(';', m.end());
            if (open < 0 || semi >= 0 && semi < open) {
                continue; // abstract, or a field initialiser
            }
            int depth = 0;
            int end = open;
            for (; end < s.length(); end++) {
                char ch = s.charAt(end);
                if (ch == '{') {
                    depth++;
                } else if (ch == '}' && --depth == 0) {
                    break;
                }
            }
            if (!KEYWORDS.contains(m.group(1))) {
                // the signature too: a sight handed in as a parameter counts
                out.merge(file + "." + m.group(1), s.substring(m.start(), Math.min(end + 1, s.length())),
                    String::concat);
            }
        }
        return out;
    }

    private static List<Path> boundary() throws IOException {
        List<Path> out = new ArrayList<>();
        for (Path dir : List.of(MAIN.resolve("api"), MAIN.resolve("ai/mcp"))) {
            try (Stream<Path> files = Files.list(dir)) {
                files.filter(f -> f.toString().endsWith(".java")).forEach(out::add);
            }
        }
        return out;
    }

    @Test
    void everyEndpointMethodThatShowsValuesAsksSights() throws IOException {
        List<String> missing = new ArrayList<>();
        List<String> own = new ArrayList<>();
        List<Path> files = boundary();
        assertTrue(files.size() > 10, "found the resources: " + files);
        Map<String, String> all = new LinkedHashMap<>();
        for (Path f : files) {
            String name = f.getFileName().toString();
            String text = Files.readString(f);
            if (OWN_MASKING.matcher(code(text)).find()) {
                own.add(name);
            }
            if (!HELPERS.contains(name)) {
                all.putAll(methods(name.replace(".java", ""), text));
            }
        }
        assertTrue(all.size() > 50, "found the methods: " + all.keySet());
        all.forEach((method, body) -> {
            if (VALUES.matcher(body).find() && !ASKS.matcher(body).find()) {
                String helper = DELEGATES.get(method);
                if (helper == null || !ASKS.matcher(all.getOrDefault(helper, "")).find()) {
                    missing.add(method);
                }
            }
        });
        assertEquals(List.of(), missing, "show values from the data without asking Sights");
        assertEquals(List.of(), own, "work out marks or a Masking.Sight themselves instead of asking Sights");
    }

    @Test
    void theCheckWouldNoticeAMethodThatForgets() {
        String forgetful = """
            class R {
                @GET
                public JsonNode history(String urn) {
                    return json.valueToTree(store.drift(urn, 50));
                }

                public JsonNode fine(String urn) {
                    return json.valueToTree(store.drift(urn, 50).stream().map(d -> Masking.drift(d, sights.of(urn,
                        false))).toList());
                }
            }
            """;
        Map<String, String> m = methods("R", forgetful);
        assertTrue(VALUES.matcher(m.get("R.history")).find() && !ASKS.matcher(m.get("R.history")).find(), m.toString());
        assertTrue(ASKS.matcher(m.get("R.fine")).find(), m.toString());
        assertTrue(OWN_MASKING.matcher("Set<String> s = quality.sensitive(urn);").find());
        assertEquals("x(\"\") { }", code("x(\"{ \\\" }\") { } // }").strip(), "strings and comments do not count");
    }
}

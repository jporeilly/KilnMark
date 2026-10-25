package io.kilnmark.server.quality;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

/**
 * Masking is decided in one place: every resource and MCP tool that returns values from the data
 * (profiles, samples, failing rows, previews, document text and snippets, exports) goes through the
 * request's {@link Sights}, and none works out an asset's marks or a {@link Masking.Sight} itself.
 * A check over the source tree, so a new endpoint that forgets fails the build.
 */
class SightsUseTest {

    private static final Path MAIN = Path.of("src/main/java/io/kilnmark/server");
    /** What reads values from the data, at the API boundary. */
    private static final Pattern VALUES = Pattern.compile("profileJson\\(\\)|failingRows\\(\\)|store\\.sample\\("
        + "|connector\\.preview\\(|texts\\.page|documents\\.chunks\\(|Masking\\.profile\\(|hybrid\\.search\\("
        + "|ask\\.ask\\(|StandardFormats\\.(odcs|openLineage)\\(");
    /** Working masking out apart from {@link Sights}. */
    private static final Pattern OWN_MASKING = Pattern.compile("(store|quality)\\.sensitive\\("
        + "|new (io\\.kilnmark\\.server\\.quality\\.)?Masking\\.Sight\\(");
    /** Helpers behind an endpoint that checks: records, and document text read for DocumentResource. */
    private static final Set<String> HELPERS = Set.of("Views.java", "BrowseViews.java", "DocumentTexts.java");

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
    void everyEndpointThatShowsValuesAsksSights() throws IOException {
        List<String> missing = new ArrayList<>();
        List<String> own = new ArrayList<>();
        List<Path> files = boundary();
        assertTrue(files.size() > 10, "found the resources: " + files);
        for (Path f : files) {
            String text = Files.readString(f);
            String name = f.getFileName().toString();
            if (!HELPERS.contains(name) && VALUES.matcher(text).find() && !text.contains("Sights sights")) {
                missing.add(name);
            }
            if (OWN_MASKING.matcher(text).find()) {
                own.add(name);
            }
        }
        assertEquals(List.of(), missing, "show values from the data without asking Sights");
        assertEquals(List.of(), own, "work out marks or a Masking.Sight themselves instead of asking Sights");
    }

    @Test
    void theCheckWouldNoticeAnEndpointThatForgets() {
        String forgetful = "return Masking.profile(parse(f.profileJson()), true, false);";
        assertTrue(VALUES.matcher(forgetful).find());
        assertTrue(OWN_MASKING.matcher("Set<String> s = quality.sensitive(urn);").find());
    }
}

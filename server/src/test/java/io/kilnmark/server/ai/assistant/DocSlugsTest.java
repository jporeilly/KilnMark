package io.kilnmark.server.ai.assistant;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Section anchors match the in-app viewer's: both are checked against tests/docs/slug-cases.json
 * (the UI by scripts/check-slugs.mjs before every build), so they cannot drift apart.
 */
class DocSlugsTest {

    static Path cases() {
        return Path.of(System.getProperty("kilnmark.docs.cases", "../tests/docs"));
    }

    static JsonNode fixture() throws IOException {
        return new ObjectMapper().readTree(cases().resolve("slug-cases.json").toFile());
    }

    @Test
    void headingsGiveTheSameSlugsAsTheViewer() throws IOException {
        JsonNode f = fixture();
        int n = 0;
        for (JsonNode c : f.path("slugs")) {
            assertEquals(c.path("slug").asText(), DocSlugs.slug(c.path("heading").asText()), c.path("heading").asText());
            n++;
        }
        assertEquals(true, n >= 20, "the fixture has its cases");
    }

    @Test
    void repeatedHeadingsOnAPageGetSuffixesAsTheViewerGivesThem() throws IOException {
        for (JsonNode page : fixture().path("pages")) {
            Map<String, Integer> seen = new HashMap<>();
            List<String> got = new ArrayList<>();
            List<String> expected = new ArrayList<>();
            page.path("headings").forEach(h -> got.add(DocSlugs.uniqueSlug(DocSlugs.slug(h.asText()), seen)));
            page.path("slugs").forEach(s -> expected.add(s.asText()));
            assertEquals(expected, got);
        }
    }

    @Test
    void plainHeadingsKeepLinkWordsAndDropMarks() {
        assertEquals("See Choosing a check", DocSlugs.plainHeading("See [Choosing a check](#choosing-a-check)"));
        assertEquals("keep_text", DocSlugs.plainHeading("`keep_text`"));
        assertEquals("no-break", DocSlugs.plainHeading("\u00A0no-break\u00A0"));
    }
}

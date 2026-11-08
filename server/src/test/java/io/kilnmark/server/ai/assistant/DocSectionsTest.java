package io.kilnmark.server.ai.assistant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/** Splitting the documentation into sections, with the viewer's anchors, from the bundled resources. */
class DocSectionsTest {

    @Test
    void headingsAreFoundAsTheViewerFindsThem() throws IOException {
        JsonNode hc = DocSlugsTest.fixture().path("headingCases");
        String md = Files.readString(DocSlugsTest.cases().resolve(hc.path("file").asText()));
        List<String> expected = new ArrayList<>();
        hc.path("slugs").forEach(s -> expected.add(s.asText()));
        assertEquals(expected, DocSections.headingSlugs(md));
    }

    @Test
    void aPageSplitsAtLevelTwoAndThreeHeadings() throws IOException {
        String md = Files.readString(DocSlugsTest.cases().resolve("heading-cases.md"));
        List<DocSections.Section> s = DocSections.split(new DocSections.Page("cases", "Heading cases", md));
        assertEquals(List.of("cases", "cases/first-section", "cases/sub-section", "cases/indented-three-spaces",
            "cases/first-section-1", "cases/ends-with-hash", "cases/c-notes", "cases/after-the-fence"),
            s.stream().map(DocSections.Section::id).toList());
        DocSections.Section first = s.get(1);
        assertEquals("Heading cases › First section", first.titlePath());
        assertTrue(first.body().contains("# not a heading"), "code is kept: " + first.body());
        assertTrue(first.body().contains("Text with code and a link."), first.body());
        assertFalse(first.body().contains("```"), first.body());
        DocSections.Section sub = s.get(2);
        assertEquals("Heading cases › First section › Sub section", sub.titlePath());
        assertTrue(sub.body().contains("Deep heading"), "deeper headings stay in their section: " + sub.body());
        assertNull(s.get(0).slug());
        assertEquals("#/docs/cases", s.get(0).href());
        assertEquals("#/docs/cases/c-notes", s.get(6).href());
    }

    @Test
    void markdownBecomesPlainText() {
        String plain = DocSections.plain("""
            > **Tip:** Look at *Other shapes* and [its examples](#x) before `accepting`.

            | Check | What fails |
            | --- | --- |
            | **Not null** | Empty values |

            ```json
            {"regex": "^[A-Z]$"}
            ```
            - keep_samples stays as written
            """);
        assertTrue(plain.contains("Tip: Look at Other shapes and its examples before accepting."), plain);
        assertTrue(plain.contains("Check | What fails"), plain);
        assertTrue(plain.contains("Not null | Empty values"), plain);
        assertFalse(plain.contains("---"), plain);
        assertTrue(plain.contains("{\"regex\": \"^[A-Z]$\"}"), plain);
        assertTrue(plain.contains("- keep_samples stays as written"), plain);
    }

    @Test
    void hrefsAreEncodedAsTheViewerEncodesThem() {
        assertEquals("#/docs/quality-rules/custom-sql-admins", DocSections.href("quality-rules", "custom-sql-admins"));
        assertEquals("#/docs/p/caf%C3%A9-%E2%80%93", DocSections.href("p", "café-–"));
        assertEquals("#/docs/whats-new", DocSections.href("whats-new", null));
    }

    @Test
    void theBundledDocumentationSplitsIntoSectionsTheViewerCanOpen() {
        List<DocSections.Page> pages = BundledDocs.pages();
        Set<String> ids = pages.stream().map(DocSections.Page::id).collect(Collectors.toSet());
        assertTrue(ids.containsAll(Set.of("quality-rules", "assistant", BundledDocs.WHATS_NEW)), ids.toString());
        assertEquals(BundledDocs.WHATS_NEW, pages.getLast().id(), "What's new comes last, as in the viewer");
        Set<String> seen = new HashSet<>();
        for (DocSections.Page p : pages) {
            Set<String> anchors = new HashSet<>(DocSections.headingSlugs(p.markdown()));
            for (DocSections.Section s : DocSections.split(p)) {
                assertTrue(seen.add(s.id()), "unique id " + s.id());
                assertTrue(s.slug() == null || anchors.contains(s.slug()), s.id());
                assertTrue(s.titlePath().startsWith(p.title()), s.titlePath());
                assertEquals(32, s.hash().length);
            }
        }
        Set<String> answered = BundledDocs.sections().stream().map(DocSections.Section::id).collect(Collectors.toSet());
        // Whatever sits under Unreleased is left out; released versions are answered from. (Which
        // "Details" slug is the unreleased one changes with every release, so test by title path.)
        assertFalse(answered.contains("whats-new/unreleased"), "the changelog's Unreleased part is not answered from");
        assertTrue(BundledDocs.sections().stream().noneMatch(s -> s.titlePath().contains("Unreleased")),
            "nothing under Unreleased is answered from");
        assertTrue(answered.contains("whats-new/002-24-october-2026"), answered.toString());
        Map<String, DocSections.Section> byId = BundledDocs.sections().stream()
            .collect(Collectors.toMap(DocSections.Section::id, Function.identity()));
        assertEquals("Setting data quality rules › Thresholds", byId.get("quality-rules/thresholds").titlePath());
        assertTrue(byId.get("quality-rules/thresholds").body().contains("Fail above"));
    }

    @Test
    void everySectionInTheEvaluationSetExists() throws IOException {
        Set<String> ids = BundledDocs.sections().stream().map(DocSections.Section::id).collect(Collectors.toSet());
        JsonNode set = new ObjectMapper().readTree(Path.of(System.getProperty("kmtest.eval.docs",
            "../tests/ai-eval/docs.json")).toFile());
        int items = 0;
        for (JsonNode item : set.path("items")) {
            items++;
            assertFalse(item.path("question").asText().isBlank());
            for (JsonNode r : item.path("relevant")) {
                assertTrue(ids.contains(r.asText()), "no section " + r.asText() + " for: " + item.path("question"));
            }
        }
        assertTrue(items >= 15 && items <= 35, "15 to 35 questions: " + items);
    }
}

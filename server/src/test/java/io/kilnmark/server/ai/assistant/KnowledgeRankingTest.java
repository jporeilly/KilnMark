package io.kilnmark.server.ai.assistant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

/** Merging rankings, the page boost, snippets and excerpts. */
class KnowledgeRankingTest {

    private static Map<String, Double> ranked(Object... idAndScore) {
        Map<String, Double> m = new LinkedHashMap<>();
        for (int i = 0; i < idAndScore.length; i += 2) {
            m.put((String) idAndScore[i], ((Number) idAndScore[i + 1]).doubleValue());
        }
        return m;
    }

    @Test
    void wordsAloneKeepTheirOrder() {
        List<KnowledgeRanking.Scored> s = KnowledgeRanking.fuse(ranked("a/x", 2.0, "b/y", 1.0), Map.of(), null);
        assertEquals(List.of("a/x", "b/y"), s.stream().map(KnowledgeRanking.Scored::id).toList());
        assertEquals(1.0, s.get(0).score());
        assertEquals(List.of("words"), s.get(0).matchedBy());
    }

    @Test
    void aSectionFoundBothWaysRises() {
        List<KnowledgeRanking.Scored> s = KnowledgeRanking.fuse(ranked("a/x", 2.0, "b/y", 1.8),
            ranked("b/y", 0.9, "c/z", 0.8), null);
        assertEquals("b/y", s.get(0).id());
        assertEquals(List.of("words", "meaning"), s.get(0).matchedBy());
    }

    @Test
    void thePagePersonIsOnRanksHigherButDoesNotSwampBetterMatches() {
        Map<String, Double> words = ranked("whats-new/details", 1.0, "quality-rules/thresholds", 0.9,
            "quality-rules/recipes", 0.3);
        List<String> boosted = KnowledgeRanking.fuse(words, Map.of(), "quality-rules").stream()
            .map(KnowledgeRanking.Scored::id).toList();
        assertEquals("quality-rules/thresholds", boosted.get(0), "a close match on the page comes first");
        assertEquals("quality-rules/recipes", boosted.get(2), "a weak match on the page stays weak");
        assertEquals("quality-rules", KnowledgeRanking.pageOf("quality-rules"));
    }

    @Test
    void snippetsShowWhereTheWordsAre() {
        String text = "Intro. ".repeat(60) + "The fail limit decides when a rule fails. " + "Tail. ".repeat(60);
        String s = KnowledgeRanking.snippet(text, "What is the fail limit?", 120);
        assertTrue(s.contains("fail limit"), s);
        assertTrue(s.startsWith("…") && s.endsWith("…"), s);
        assertTrue(s.length() <= 124, s);
        assertEquals("Short text.", KnowledgeRanking.snippet("Short\ntext.", "x", 120));
    }

    @Test
    void longSectionsAreCutToThePassagesThatAnswer() {
        String text = "- **Sign-in.** Tokens and roles.\n"
            + "- **Sources.** " + "Many kinds of source. ".repeat(30) + "\n"
            + "- **Find local models** on the AI models page: looks for Ollama and LM Studio.\n"
            + "- **Documents.** " + "PDF and Word. ".repeat(30) + "\n";
        String e = KnowledgeRanking.excerpt(text, "How do I find Ollama models?", 300);
        assertTrue(e.contains("Ollama"), e);
        assertTrue(e.length() <= 310, e.length() + ": " + e);
        assertFalse(e.contains("PDF and Word. PDF"), e);
        assertEquals("short", KnowledgeRanking.excerpt("short", "x", 300));
    }
}

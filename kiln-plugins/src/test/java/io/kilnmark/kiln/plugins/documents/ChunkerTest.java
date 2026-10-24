package io.kilnmark.kiln.plugins.documents;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

class ChunkerTest {

    @Test
    void packsWholeParagraphsUpToTheSize() {
        String text = "First paragraph here.\n\nSecond one.\n\nThird paragraph is a bit longer than the others.";
        List<Chunker.Chunk> c = Chunker.chunks(text, List.of(), 50, 0);
        assertEquals(List.of("First paragraph here.\n\nSecond one.", "Third paragraph is a bit longer than the others."),
            c.stream().map(Chunker.Chunk::text).toList());
        for (Chunker.Chunk k : c) {
            assertEquals(k.text(), text.substring(k.start(), k.end()), "offsets point into the text");
        }
        assertEquals(List.of(0, 1), c.stream().map(Chunker.Chunk::sequence).toList());
    }

    @Test
    void splitsLongParagraphsAtSentencesAndOverlaps() {
        String sentence = "The quick brown fox jumps over the lazy dog. ";
        String text = sentence.repeat(10).strip();
        List<Chunker.Chunk> c = Chunker.chunks(text, List.of(), 100, 20);
        assertTrue(c.size() >= 5, c.toString());
        for (int i = 0; i < c.size(); i++) {
            Chunker.Chunk k = c.get(i);
            assertTrue(k.text().length() <= 120, k.text());
            assertEquals(k.text(), text.substring(k.start(), k.end()));
            if (i > 0) {
                assertTrue(k.start() < c.get(i - 1).end(), "overlaps the chunk before");
                assertTrue(Character.isWhitespace(text.charAt(k.start() - 1)), "starts at a word");
            }
        }
        assertEquals(text.length(), c.get(c.size() - 1).end(), "covers the end");
    }

    @Test
    void cutsTextWithoutSpacesHard() {
        String text = "x".repeat(250);
        List<Chunker.Chunk> c = Chunker.chunks(text, List.of(), 100, 10);
        assertEquals(3, c.size());
        assertEquals(250, c.get(2).end());
    }

    @Test
    void pagesComeFromTheStart() {
        String text = "Page one text.\n\nPage two text.\n\nStill page two.";
        List<Chunker.Chunk> c = Chunker.chunks(text, List.of(0, 16), 100, 0);
        assertEquals(1, c.size());
        assertEquals(1, c.get(0).page());
        c = Chunker.chunks(text, List.of(0, 16), 16, 0);
        assertEquals(List.of(1, 2, 2), c.stream().map(Chunker.Chunk::page).toList());
        assertEquals(null, Chunker.chunks(text, List.of(), 100, 0).get(0).page());
    }

    @Test
    void emptyTextHasNoChunks() {
        assertEquals(List.of(), Chunker.chunks("", List.of(), 100, 10));
        assertEquals(List.of(), Chunker.chunks("  \n\n  ", List.of(), 100, 10));
    }

    @Test
    void theOverlapDoesNotDecideTheChunksPage() {
        String one = "Words on page one. ".repeat(4).strip();
        String two = "Words on page two. ".repeat(4).strip();
        String text = one + "\n\n" + two;
        List<Chunker.Chunk> c = Chunker.chunks(text, List.of(0, one.length() + 2), 80, 30);
        assertEquals(2, c.size());
        assertTrue(c.get(1).start() < one.length(), "the second chunk starts with the overlap from page one");
        assertEquals(2, c.get(1).page(), "but its own text is on page two");
    }
}

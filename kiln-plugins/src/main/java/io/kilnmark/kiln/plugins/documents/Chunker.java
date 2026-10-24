package io.kilnmark.kiln.plugins.documents;

import java.util.ArrayList;
import java.util.List;

/**
 * Splits a document's text into chunks for search and, later, for embedding: whole paragraphs
 * are packed into a chunk until it would pass the size, a paragraph longer than the size is split
 * at a sentence end or a space, and each chunk after the first starts with the last few words of
 * the one before (the overlap), so a sentence cut between two chunks is whole in one of them.
 *
 * <p>A chunk's text is exactly {@code text.substring(start, end)}, so offsets point into the text,
 * and together the chunks cover all of it but the blank space between them. A chunk is at most the
 * size plus the overlap.
 */
public final class Chunker {

    /**
     * One chunk.
     *
     * @param sequence 0-based, in text order
     * @param page     the page, slide or sheet its own text starts on (1-based; the overlap repeated
     *                 from the chunk before does not count), or null when not known
     */
    public record Chunk(int sequence, String text, Integer page, int start, int end) {
    }

    private Chunker() {
    }

    /**
     * @param pageStarts where each page starts in {@code text}, ascending; empty when not known
     * @param size       the most characters a chunk aims for
     * @param overlap    characters repeated from the end of the chunk before
     */
    public static List<Chunk> chunks(String text, List<Integer> pageStarts, int size, int overlap) {
        List<int[]> pieces = pieces(text, size);
        List<Chunk> out = new ArrayList<>();
        int i = 0;
        int carry = -1;
        while (i < pieces.size()) {
            int start = carry >= 0 ? carry : pieces.get(i)[0];
            int end = pieces.get(i)[1];
            int j = i + 1;
            while (j < pieces.size() && pieces.get(j)[1] - start <= size) {
                end = pieces.get(j)[1];
                j++;
            }
            // the page is where the chunk's own text starts, not the overlap repeated from before
            out.add(new Chunk(out.size(), text.substring(start, end), page(pageStarts, pieces.get(i)[0]), start, end));
            carry = -1;
            if (j < pieces.size() && overlap > 0) {
                int from = wordStart(text, Math.max(end - overlap, start + 1), end);
                if (from >= 0 && from < end) {
                    carry = from;
                }
            }
            i = j;
        }
        return out;
    }

    /** Paragraphs as [start, end) without surrounding white space; those longer than {@code size} split. */
    static List<int[]> pieces(String text, int size) {
        List<int[]> out = new ArrayList<>();
        int n = text.length();
        int p = 0;
        while (p < n) {
            int brk = text.indexOf("\n\n", p);
            int end = brk < 0 ? n : brk;
            int s = p;
            int e = end;
            while (s < e && Character.isWhitespace(text.charAt(s))) {
                s++;
            }
            while (e > s && Character.isWhitespace(text.charAt(e - 1))) {
                e--;
            }
            while (e - s > size) {
                int cut = cut(text, s, s + size);
                out.add(new int[] {s, cut});
                s = cut;
                while (s < e && Character.isWhitespace(text.charAt(s))) {
                    s++;
                }
            }
            if (e > s) {
                out.add(new int[] {s, e});
            }
            p = brk < 0 ? n : brk + 2;
        }
        return out;
    }

    /** Where to cut a long paragraph before {@code limit}: after a sentence, else at a space, else at the limit. */
    private static int cut(String text, int from, int limit) {
        int floor = from + (limit - from) / 2;
        for (int k = limit; k > floor; k--) {
            char c = text.charAt(k - 1);
            if ((c == '.' || c == '!' || c == '?' || c == '\n') && Character.isWhitespace(text.charAt(k))) {
                return k;
            }
        }
        for (int k = limit; k > floor; k--) {
            if (Character.isWhitespace(text.charAt(k))) {
                return k;
            }
        }
        return Character.isLowSurrogate(text.charAt(limit)) ? limit - 1 : limit;
    }

    /** The first word start at or after {@code from} and before {@code end}, or -1. */
    private static int wordStart(String text, int from, int end) {
        for (int k = Math.max(from, 1); k < end; k++) {
            if (Character.isWhitespace(text.charAt(k - 1)) && !Character.isWhitespace(text.charAt(k))) {
                return k;
            }
        }
        return -1;
    }

    /** The 1-based page holding {@code offset}, or null when there are no pages. */
    static Integer page(List<Integer> pageStarts, int offset) {
        if (pageStarts.isEmpty()) {
            return null;
        }
        int lo = 0;
        int hi = pageStarts.size() - 1;
        int found = 0;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            if (pageStarts.get(mid) <= offset) {
                found = mid;
                lo = mid + 1;
            } else {
                hi = mid - 1;
            }
        }
        return found + 1;
    }
}

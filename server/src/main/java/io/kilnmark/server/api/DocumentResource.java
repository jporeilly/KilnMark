package io.kilnmark.server.api;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import io.kilnmark.graph.store.DocumentRecords;
import io.kilnmark.graph.store.DocumentStore;
import io.kilnmark.server.auth.AccessRole;
import jakarta.annotation.security.RolesAllowed;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;

/**
 * Reading documents (ADR 0017): their text page by page, finding words in them, and their stored
 * chunks. The asset is named by {@code urn} in the query, since URNs hold slashes. Text is always
 * plain text, never markup, so nothing in a document can run in a browser.
 */
@Path("/api/v1/documents")
@Produces(MediaType.APPLICATION_JSON)
@RolesAllowed(AccessRole.VIEWER_ROLE)
public class DocumentResource {

    /** Words found are counted up to this many. */
    static final int MAX_MATCHES = 10_000;

    private final DocumentTexts texts;
    private final DocumentStore documents;

    public DocumentResource(DocumentTexts texts, DocumentStore documents) {
        this.texts = texts;
        this.documents = documents;
    }

    private static String urn(String urn) {
        if (urn == null || urn.isBlank()) {
            throw new IllegalArgumentException("urn: the document's identifier, for example "
                + "urn:kilnmark:share:policies/remote-working.pdf");
        }
        if (!urn.startsWith("urn:kilnmark:")) {
            throw new IllegalArgumentException("urn: not a Kilnmark identifier: " + urn);
        }
        return urn.strip();
    }

    /** One page (or slide, sheet, part) of a document's text. */
    @GET
    @Path("text")
    public Views.DocumentPage text(@QueryParam("urn") String urn, @QueryParam("page") Integer page) {
        DocumentTexts.Text t = texts.text(urn(urn));
        int n = page == null ? 1 : page;
        if (n < 1 || n > t.pages().size()) {
            throw new IllegalArgumentException("page: from 1 to " + t.pages().size() + ", not " + n);
        }
        return new Views.DocumentPage(urn.strip(), n, t.pages().size(), t.pageKind(), labels(t),
            DocumentTexts.page(t, n), t.truncated(), t.extractedNow());
    }

    private static List<Views.PageLabel> labels(DocumentTexts.Text t) {
        return t.pages().stream().map(p -> new Views.PageLabel(p.number(), p.label())).toList();
    }

    /** Where words occur in a document: each page with matches, how many, and the first in context. */
    @GET
    @Path("find")
    public Views.FindResult find(@QueryParam("urn") String urn, @QueryParam("q") String q) {
        if (q == null || q.strip().length() < 2 || q.length() > 200) {
            throw new IllegalArgumentException("q: from 2 to 200 characters to find");
        }
        String needle = q.strip().toLowerCase(Locale.ROOT);
        DocumentTexts.Text t = texts.text(urn(urn));
        List<Views.FindPage> pages = new ArrayList<>();
        int total = 0;
        for (int i = 1; i <= t.pages().size() && total < MAX_MATCHES; i++) {
            String text = DocumentTexts.page(t, i);
            String lower = text.toLowerCase(Locale.ROOT);
            int count = 0;
            int first = -1;
            for (int at = lower.indexOf(needle); at >= 0 && total + count < MAX_MATCHES;
                 at = lower.indexOf(needle, at + needle.length())) {
                if (first < 0) {
                    first = at;
                }
                count++;
            }
            if (count > 0) {
                total += count;
                pages.add(new Views.FindPage(i, t.pages().get(i - 1).label(), count,
                    snippet(text, first, needle.length())));
            }
        }
        return new Views.FindResult(q.strip(), total, pages);
    }

    /** About 60 characters either side of a match, on one line, with the match marked by position. */
    static Views.Snippet snippet(String text, int at, int length) {
        int from = Math.max(0, at - 60);
        int to = Math.min(text.length(), at + length + 60);
        while (from > 0 && from < at && !Character.isWhitespace(text.charAt(from - 1))) {
            from++;
        }
        String before = (from > 0 ? "…" : "") + text.substring(from, at).replaceAll("\\s+", " ");
        String match = text.substring(at, at + length).replaceAll("\\s+", " ");
        String after = text.substring(at + length, to).replaceAll("\\s+", " ") + (to < text.length() ? "…" : "");
        return new Views.Snippet(before + match + after, List.of(new Views.Highlight(before.length(), match.length())));
    }

    /**
     * Stored chunks of a document's text, in order; for search and for steps that work on chunks.
     * None for sources that keep no text.
     */
    @GET
    @Path("chunks")
    public Views.Chunks chunks(@QueryParam("urn") String urn, @QueryParam("offset") Integer offset,
                               @QueryParam("limit") Integer limit) {
        DocumentRecords.Document d = texts.readable(urn(urn));
        int from = offset == null ? 0 : offset;
        if (from < 0) {
            throw new IllegalArgumentException("offset: 0 or more");
        }
        int n = limit == null ? 5 : limit;
        if (n < 1 || n > 100) {
            throw new IllegalArgumentException("limit: from 1 to 100");
        }
        List<Views.Chunk> chunks = documents.chunks(urn.strip(), from, n).stream()
            .map(c -> new Views.Chunk(c.sequence(), c.text(), c.page(), c.start(), c.end())).toList();
        return new Views.Chunks(urn.strip(), from, d.chunkCount(), d.textKept(), chunks);
    }
}

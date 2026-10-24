package io.kilnmark.server.api;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
        String u = urn(urn);
        DocumentTexts.Layout l = texts.layout(u);
        int n = page == null ? 1 : page;
        if (n < 1 || n > l.pages().size()) {
            throw new IllegalArgumentException("page: from 1 to " + l.pages().size() + ", not " + n);
        }
        return new Views.DocumentPage(u, n, l.pages().size(), l.pageKind(), labels(l), texts.page(u, l, n),
            l.document().truncated(), l.onDemand());
    }

    private static List<Views.PageLabel> labels(DocumentTexts.Layout l) {
        return l.pages().stream().map(p -> new Views.PageLabel(p.number(), p.label())).toList();
    }

    /** Where words occur in a document: each page with matches, how many, and the first in context. */
    @GET
    @Path("find")
    public Views.FindResult find(@QueryParam("urn") String urn, @QueryParam("q") String q) {
        if (q == null || q.strip().length() < 2 || q.length() > 200) {
            throw new IllegalArgumentException("q: from 2 to 200 characters to find");
        }
        String u = urn(urn);
        // matched on the text itself, ignoring case the Unicode way, so offsets are always the text's
        // own (lower-casing can change a string's length: Turkish İ becomes two characters)
        Pattern needle = Pattern.compile(Pattern.quote(q.strip()), Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
        DocumentTexts.Layout l = texts.layout(u);
        List<String> pages = texts.pages(u, l);
        List<Views.FindPage> found = new ArrayList<>();
        int total = 0;
        for (int i = 1; i <= pages.size() && total < MAX_MATCHES; i++) {
            Matcher m = needle.matcher(pages.get(i - 1));
            int count = 0;
            int first = -1;
            int firstEnd = -1;
            while (total + count < MAX_MATCHES && m.find()) {
                if (first < 0) {
                    first = m.start();
                    firstEnd = m.end();
                }
                count++;
            }
            if (count > 0) {
                total += count;
                found.add(new Views.FindPage(i, l.pages().get(i - 1).label(), count,
                    snippet(pages.get(i - 1), first, firstEnd - first)));
            }
        }
        return new Views.FindResult(q.strip(), total, found);
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

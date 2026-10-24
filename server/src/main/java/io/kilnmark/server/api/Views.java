package io.kilnmark.server.api;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;

import io.kilnmark.graph.store.Records;

/** What the API returns. Kept apart from the store's records so either can change alone. */
public final class Views {

    private Views() {
    }

    public record Run(long id, String source, String kind, String status, Instant requestedAt, Instant startedAt,
                      Instant finishedAt, int assetsSeen, int assetsFailed, long rowsRead, String detail) {
        static Run of(Records.Run r) {
            return r == null ? null : new Run(r.id(), r.source(), r.kind(), r.status(), r.requestedAt(), r.startedAt(),
                r.finishedAt(), r.assetsSeen(), r.assetsFailed(), r.rowsRead(), r.detail());
        }
    }

    public record Source(String name, String kind, Map<String, String> settings, boolean hasPassword,
                         Instant createdAt, Run lastRun) {
    }

    public record Setting(String key, String label, boolean required, String hint, boolean multiple) {
    }

    public record Connector(String kind, String displayName, List<Setting> settings) {
    }

    /** {@code snippet}: for a document whose text matched, a passage around the match; else null. */
    public record SearchHit(String urn, String source, String kind, String container, String name,
                            String description, Long rowCount, List<String> matchedFields, double score,
                            Snippet snippet) {
        static SearchHit of(Records.SearchHit h) {
            return new SearchHit(h.urn(), h.source(), h.kind(), h.container(), h.name(), h.description(), h.rowCount(),
                h.matchedFields(), h.score(), Snippet.of(h.snippet()));
        }
    }

    /** A passage of text; {@code highlights} mark the matched words by position (never markup). */
    public record Snippet(String text, List<Highlight> highlights) {
        static Snippet of(io.kilnmark.graph.store.DocumentRecords.Snippet s) {
            return s == null ? null : new Snippet(s.text(), s.highlights().stream()
                .map(h -> new Highlight(h.start(), h.length())).toList());
        }
    }

    public record Highlight(int start, int length) {
    }

    public record SearchResult(String query, List<SearchHit> hits) {
    }

    public record Field(String name, int position, String sourceType, String kind, boolean nullable,
                        String description, JsonNode profile, Instant profiledAt) {
    }

    /** An API token as listed: never its secret or hash. {@code status} is active, expired or revoked. */
    public record Token(long id, String name, String role, String status, String createdBy, Instant createdAt,
                        Instant expiresAt, Instant lastUsedAt, Instant revokedAt) {
        static Token of(io.kilnmark.server.auth.ApiTokens.Token t, Instant now) {
            return new Token(t.id(), t.name(), t.role().id(), t.status(now), t.createdBy(), t.createdAt(),
                t.expiresAt(), t.lastUsedAt(), t.revokedAt());
        }
    }

    /** A token just created. {@code token} is shown this once and cannot be read again. */
    public record NewToken(long id, String name, String role, Instant createdAt, Instant expiresAt, String token) {
    }

    /** Who is signed in. */
    public record Me(String name, String role, Long tokenId, Instant expiresAt, boolean signInRequired) {
    }

    /**
     * An asset. For documents (ADR 0017): {@code document} is what reading it found; {@code parent} the
     * asset it sits inside (a sheet's workbook, an attachment's message) and {@code parts} the assets
     * inside it. Null and empty for other assets.
     */
    public record Asset(String urn, String source, String kind, String container, String name, String description,
                        List<String> primaryKey, Long rowCount, Boolean rowCountComplete, Instant firstSeenAt,
                        Instant lastScannedAt, Instant removedAt, List<Field> fields, DocumentProfile document,
                        AssetLink parent, List<AssetLink> parts) {
    }

    /**
     * What reading a document found. {@code mail} is null for documents that are not mail.
     * {@code textOnDemand}: no text is kept, but stewards may have it read from the source.
     */
    public record DocumentProfile(boolean readable, String problem, String format, String contentType, Long size,
                                  Integer pages, Long words, Long characters, String language, String title,
                                  String author, Instant created, Instant modified, String owner,
                                  Map<String, String> tags, Mail mail, List<String> notes, boolean truncated,
                                  boolean textKept, boolean textOnDemand, int chunks, List<PageLabel> pageList,
                                  Instant readAt) {
        static DocumentProfile of(io.kilnmark.graph.store.DocumentRecords.Document d, boolean onDemand) {
            if (d == null) {
                return null;
            }
            Map<String, String> m = d.mail();
            return new DocumentProfile(d.readable(), d.problem(), d.format(), d.contentType(), d.sizeBytes(), d.pages(),
                d.words(), d.characters(), d.language(), d.title(), d.author(), d.created(), d.modified(), d.owner(),
                d.tags(), m.isEmpty() ? null : new Mail(m.get("from"), m.get("to"), m.get("cc"), m.get("subject"),
                m.get("date")), d.notes(), d.truncated(), d.textKept(), !d.textKept() && onDemand, d.chunkCount(),
                d.pageList().stream().map(p -> new PageLabel(p.number(), p.label())).toList(), d.observedAt());
        }
    }

    /** A mail message's headers. {@code date} is ISO 8601. */
    public record Mail(String from, String to, String cc, String subject, String date) {
    }

    /** A link to another asset: a sheet, an attachment, a workbook or a message. */
    public record AssetLink(String urn, String kind, String name, boolean readable, String description,
                            boolean removed) {
        static AssetLink of(io.kilnmark.graph.store.DocumentRecords.Part p) {
            return p == null ? null : new AssetLink(p.urn(), p.kind(), p.name(), p.readable(), p.description(),
                p.removedAt() != null);
        }
    }

    /** A page, slide, sheet or part of a document's text; {@code label} is a sheet's name, or null. */
    public record PageLabel(int number, String label) {
    }

    /** One page of a document's text, for reading. {@code extractedNow}: read from the source just now. */
    public record DocumentPage(String urn, int page, int pageCount, String pageKind, List<PageLabel> pages,
                               String text, boolean truncated, boolean extractedNow) {
    }

    /** Where words occur in a document, page by page. */
    public record FindResult(String query, int total, List<FindPage> pages) {
    }

    public record FindPage(int number, String label, int count, Snippet snippet) {
    }

    public record Chunk(int sequence, String text, Integer page, int start, int end) {
    }

    /** Stored chunks of a document's text, from {@code offset}; {@code total} chunks in all. */
    public record Chunks(String urn, int offset, int total, boolean textKept, List<Chunk> chunks) {
    }
}

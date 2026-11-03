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

    /** A source. {@code name} is its fixed key, part of every asset URN; people see {@code displayName}. */
    public record Source(String name, String kind, Map<String, String> settings, boolean hasPassword,
                         Instant createdAt, Run lastRun, String displayName, int assets, String category,
                         Map<String, Integer> formats) {
    }

    /**
     * One setting of a kind of source. {@code example} is a sample value for forms, or null.
     * {@code kind} is {@code text}, {@code number}, {@code boolean}, {@code choice} or {@code url};
     * {@code choices} lists a choice's values (empty otherwise); {@code defaultValue} is the value used
     * when the setting is left empty, as stored text, or null.
     */
    public record Setting(String key, String label, boolean required, String hint, boolean multiple, String example,
                          String kind, List<String> choices, String defaultValue) {

        public Setting(String key, String label, boolean required, String hint, boolean multiple, String example) {
            this(key, label, required, hint, multiple, example, "text", List.of(), null);
        }
    }

    /**
     * A kind of source. {@code containerSetting} is the list setting holding the containers chosen
     * for scanning (schemas, folders), {@code containerLabel} what they are called and
     * {@code containerCounted} what their counts count. {@code driver} says whether its driver is
     * there: {@code bundled} (it ships with Kilnmark), {@code ready} (installed by the customer and
     * verified), or {@code missing}, {@code mismatch} or {@code unreadable}, when the source needs
     * its driver installed under Settings › Drivers.
     */
    public record Connector(String kind, String displayName, List<Setting> settings, String containerSetting,
                            String containerLabel, String containerCounted, String category, String family,
                            List<String> capabilities, List<String> formats, String icon, String monogram,
                            String docsAnchor, boolean addressFields, boolean driverBundled, String driver) {
    }

    /**
     * A connector that is planned, not built: shown greyed out. {@code id} is the kind it is expected
     * to have, so a branding manifest can name it; {@code monogram} is the letters on its tile, or
     * null for a generic connector, whose tile shows an icon.
     */
    public record PlannedConnector(String id, String name, String category, String family,
                                   List<String> capabilities, String wave, String monogram) {
    }

    /** One category of the catalogue, with its built connectors and, unless hidden, the planned ones. */
    public record ConnectorCategory(String id, String label, List<Connector> connectors,
                                    List<PlannedConnector> planned) {
    }

    /** The catalogue of connectors, by category. */
    public record ConnectorCatalogue(boolean showPlanned, List<ConnectorCategory> categories) {
    }

    /** A page of the tables or files in one container. */
    public record AssetList(String container, int total, int offset, List<AssetItem> items) {
    }

    /** A table or file, without its fields. {@code readable} is false for documents, which have no rows. */
    public record AssetItem(String path, String name, String kind, boolean readable) {
    }

    /**
     * The first rows of an asset, read live and never stored. {@code truncated}: the size cap
     * stopped it early; {@code details}: what was detected about a file.
     */
    public record Preview(String asset, List<PreviewColumn> columns, List<List<String>> rows, int rowCount,
                          boolean truncated, Map<String, String> details) {
    }

    public record PreviewColumn(String name, String kind, String sourceType) {
    }

    /**
     * A setting every source has. {@code choices} lists the values allowed; empty for free text.
     * {@code kind} is {@code text}, {@code number}, {@code boolean} or {@code choice}, as for
     * {@link Setting}.
     */
    public record CommonSetting(String key, String label, String hint, boolean multiple, List<String> choices,
                                String defaultValue, String kind) {
    }

    /** The outcome of a connection test. {@code problem} is null when it worked; it never holds a secret. */
    public record TestResult(boolean ok, String problem, long millis) {
    }

    /** One container (schema, folder) and how many tables or files in it the source's patterns keep. */
    public record ContainerItem(String name, long count, boolean selected) {
    }

    /**
     * What a source holds, for choosing what to scan. {@code selected} is the source's choice
     * (empty: everything); {@code missing} are chosen names the source no longer has; {@code total}
     * counts what a scan would read now. {@code nothingToScan} is true when that is nothing, and
     * {@code warnings} say why, for people to read before they scan.
     */
    public record ContainerList(String setting, String label, String counted, List<ContainerItem> containers,
                                List<String> selected, List<String> missing, long total, boolean nothingToScan,
                                List<String> warnings) {
    }

    /** One run in a source's history, with its job's attempts and last error (null when it has none). */
    public record HistoryRun(Run run, Integer attempts, String jobError) {
    }

    /** What deleting a source removed. */
    public record Deleted(String name, int assets, int runs) {
    }

    /**
     * A bulk import's follow-up work. {@code status}: running, finished, or stopped (the server
     * running it stopped; run the import again).
     */
    public record ImportBatch(long id, String status, boolean dryRun, boolean test, boolean scan, Instant createdAt,
                              Instant finishedAt, List<ImportBatchRow> rows) {
    }

    /**
     * One row's progress. {@code test}: waiting, testing, ok, failed or skipped; {@code scan}:
     * waiting, queued, skipped or failed. {@code problem} says what went wrong, never with a secret.
     */
    public record ImportBatchRow(int line, String name, String test, String problem, String scan, Long runId) {
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
        public static Snippet of(io.kilnmark.graph.store.DocumentRecords.Snippet s) {
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

    /**
     * An API token as listed: never its secret or hash. {@code status} is active, expired or revoked;
     * {@code unmask} says whether it sees values of columns marked sensitive (designer tokens only);
     * {@code unmaskRequestedBy}, who asked for that grant while it waits for a second admin (ADR 0038).
     */
    public record Token(long id, String name, String role, boolean unmask, String status, String createdBy,
                        Instant createdAt, Instant expiresAt, Instant lastUsedAt, Instant revokedAt,
                        String unmaskRequestedBy) {
        static Token of(io.kilnmark.server.auth.ApiTokens.Token t, Instant now) {
            return new Token(t.id(), t.name(), t.role().id(), t.unmask(), t.status(now), t.createdBy(), t.createdAt(),
                t.expiresAt(), t.lastUsedAt(), t.revokedAt(), t.revokedAt() == null ? t.unmaskRequestedBy() : null);
        }
    }

    /** A token just created. {@code token} is shown this once and cannot be read again. */
    public record NewToken(long id, String name, String role, boolean unmask, Instant createdAt, Instant expiresAt,
                           String token, String unmaskRequestedBy) {
    }

    /**
     * Who is signed in; {@code unmask}: whether they see values of columns marked sensitive;
     * {@code via}: {@code token}, {@code provider} (the identity provider, ADR 0036) or {@code off}.
     */
    public record Me(String name, String role, Long tokenId, Instant expiresAt, boolean signInRequired,
                     boolean unmask, String via) {
    }

    /**
     * Sign-in as the sign-in page needs it, signed in or not: whether the identity provider is set up
     * and its name, whether a session with it is open, and why it gives no role when it does not.
     */
    public record AuthStatus(boolean provider, String providerName, boolean signedIn, String refusal) {
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

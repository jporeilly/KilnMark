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
                         Instant createdAt, Run lastRun, String displayName, int assets) {
    }

    public record Setting(String key, String label, boolean required, String hint, boolean multiple) {
    }

    /**
     * A kind of source. {@code containerSetting} is the list setting holding the containers chosen
     * for scanning (schemas, folders), {@code containerLabel} what they are called and
     * {@code containerCounted} what their counts count.
     */
    public record Connector(String kind, String displayName, List<Setting> settings, String containerSetting,
                            String containerLabel, String containerCounted) {
    }

    /** A setting every source has. {@code choices} lists the values allowed; empty for free text. */
    public record CommonSetting(String key, String label, String hint, boolean multiple, List<String> choices,
                                String defaultValue) {
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

    public record SearchHit(String urn, String source, String kind, String container, String name,
                            String description, Long rowCount, List<String> matchedFields, double score) {
        static SearchHit of(Records.SearchHit h) {
            return new SearchHit(h.urn(), h.source(), h.kind(), h.container(), h.name(), h.description(), h.rowCount(),
                h.matchedFields(), h.score());
        }
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

    public record Asset(String urn, String source, String kind, String container, String name, String description,
                        List<String> primaryKey, Long rowCount, Boolean rowCountComplete, Instant firstSeenAt,
                        Instant lastScannedAt, Instant removedAt, List<Field> fields) {
    }
}

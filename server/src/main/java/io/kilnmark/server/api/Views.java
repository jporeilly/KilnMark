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

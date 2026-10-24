package io.kilnmark.graph.store;

import java.time.Instant;
import java.util.List;

/** The catalogue's records, as the store reads and writes them. */
public final class Records {

    private Records() {
    }

    /** A data source to register. {@code secret} is the password, already encrypted. */
    public record NewSource(String name, String kind, String jdbcUrl, String username, byte[] secret,
                            List<String> schemas) {
        public NewSource {
            schemas = List.copyOf(schemas == null ? List.of() : schemas);
        }
    }

    /** A registered data source. The secret is never part of it. */
    public record Source(long id, String name, String kind, String jdbcUrl, String username, List<String> schemas,
                         boolean hasSecret, Instant createdAt) {
    }

    public record Run(long id, String source, String kind, String status, Instant requestedAt, Instant startedAt,
                      Instant finishedAt, int assetsSeen, int assetsFailed, long rowsRead, String detail) {
    }

    public record Asset(String urn, String kind, String schema, String name, String description,
                        List<String> primaryKey) {
        public Asset {
            primaryKey = List.copyOf(primaryKey == null ? List.of() : primaryKey);
        }
    }

    public record Field(String name, int position, String sourceType, String kind, boolean nullable,
                        String description) {
    }

    public record SearchHit(String urn, String source, String kind, String schema, String name, String description,
                            Long rowCount, List<String> matchedFields, double score) {
    }

    public record FieldDetail(String name, int position, String sourceType, String kind, boolean nullable,
                              String description, String profileJson, Instant profiledAt) {
    }

    public record AssetDetail(String urn, String source, String kind, String schema, String name,
                              String description, List<String> primaryKey, Long rowCount, Boolean rowCountComplete,
                              Instant firstSeenAt, Instant lastScannedAt, Instant removedAt,
                              List<FieldDetail> fields) {
    }
}

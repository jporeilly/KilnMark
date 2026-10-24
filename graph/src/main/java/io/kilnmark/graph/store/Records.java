package io.kilnmark.graph.store;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/** The catalogue's records, as the store reads and writes them. */
public final class Records {

    private Records() {
    }

    /**
     * A data source to register: a connector kind and its settings. {@code secret} is already
     * encrypted. {@code displayName} is what people see; null means the name.
     */
    public record NewSource(String name, String kind, Map<String, String> settings, byte[] secret,
                            String displayName) {
        public NewSource {
            settings = Map.copyOf(settings == null ? Map.of() : settings);
        }

        public NewSource(String name, String kind, Map<String, String> settings, byte[] secret) {
            this(name, kind, settings, secret, null);
        }
    }

    /**
     * A registered data source. The secret is never part of it. {@code name} is its fixed key, part
     * of every asset URN; {@code displayName} is what people see, and can change.
     */
    public record Source(long id, String name, String kind, Map<String, String> settings, boolean hasSecret,
                         Instant createdAt, String displayName) {
        public Source {
            settings = Map.copyOf(settings == null ? Map.of() : settings);
            displayName = displayName == null ? name : displayName;
        }
    }

    public record Run(long id, String source, String kind, String status, Instant requestedAt, Instant startedAt,
                      Instant finishedAt, int assetsSeen, int assetsFailed, long rowsRead, String detail) {
    }

    /** {@code container}: where the asset sits, such as a schema, folder or bucket. */
    public record Asset(String urn, String kind, String container, String name, String description,
                        List<String> primaryKey) {
        public Asset {
            primaryKey = List.copyOf(primaryKey == null ? List.of() : primaryKey);
        }
    }

    public record Field(String name, int position, String sourceType, String kind, boolean nullable,
                        String description) {
    }

    public record SearchHit(String urn, String source, String kind, String container, String name, String description,
                            Long rowCount, List<String> matchedFields, double score) {
    }

    public record FieldDetail(String name, int position, String sourceType, String kind, boolean nullable,
                              String description, String profileJson, Instant profiledAt) {
    }

    public record AssetDetail(String urn, String source, String kind, String container, String name,
                              String description, List<String> primaryKey, Long rowCount, Boolean rowCountComplete,
                              Instant firstSeenAt, Instant lastScannedAt, Instant removedAt,
                              List<FieldDetail> fields) {
    }

    /**
     * New values for a registered source; its name never changes. The secret is replaced only
     * when {@code replaceSecret} is true ({@code secret} null removes it), and is otherwise kept as
     * it is. {@code secret} is already encrypted. {@code displayName} null means the name.
     * {@code keepSecretIf}: when the secret is kept, the settings (a null value meaning absent) the
     * stored source must still have, checked on the locked row, or the change is refused; these
     * are the settings that decide where the secret is sent.
     */
    public record SourceChange(String displayName, Map<String, String> settings, boolean replaceSecret,
                               byte[] secret, Map<String, String> keepSecretIf) {
        public SourceChange {
            settings = Map.copyOf(settings == null ? Map.of() : settings);
            keepSecretIf = keepSecretIf == null ? Map.of() : java.util.Collections.unmodifiableMap(
                new java.util.HashMap<>(keepSecretIf));
        }

        public SourceChange(String displayName, Map<String, String> settings, boolean replaceSecret, byte[] secret) {
            this(displayName, settings, replaceSecret, secret, null);
        }
    }

    /** What deleting a source removed. */
    public record Deleted(String name, int assets, int runs) {
    }

    /** A run with its job's attempts and last error, for a source's history; both null when it has no job. */
    public record RunHistory(Run run, Integer attempts, String jobError) {
    }
}

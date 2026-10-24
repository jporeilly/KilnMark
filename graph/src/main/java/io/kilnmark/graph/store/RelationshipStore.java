package io.kilnmark.graph.store;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Relationships between assets (migration V8): foreign keys sources declare, and joins inferred
 * for a person to accept or reject. Also keeps each column's distinct-value sketch beside its
 * profile, for inference.
 *
 * <p>Declared relationships are always {@code accepted}. Inferred ones start {@code proposed};
 * a person's decision ({@code accepted} or {@code rejected}) survives every later scan.
 */
public interface RelationshipStore {

    /** A foreign key as a scan found it, by asset URN; fields in key order. */
    record Declared(String fromUrn, List<String> fromFields, String toUrn, List<String> toFields, String name) {
        public Declared {
            fromFields = List.copyOf(fromFields);
            toFields = List.copyOf(toFields);
        }
    }

    /** An inferred single-column relationship, with its confidence and evidence (a JSON object). */
    record Inferred(String fromUrn, String fromField, String toUrn, String toField, double confidence,
                    String evidenceJson) {
    }

    /** One column of one asset. */
    record FieldRef(String urn, String field) {
    }

    /**
     * A column that could be one end of an inferred relationship: an identifier-like name, or the
     * whole primary key of its asset; with its latest profile counts ({@code profiled} false when it has none).
     */
    record KeyColumn(String assetUrn, String source, String assetName, String field, String kind, boolean primaryKey,
                     boolean profiled, long rows, long nulls, long distinct) {
    }

    /** One end of a relationship, with enough about its asset to show and link it. */
    record End(String urn, String source, String container, String name, String kind, List<String> fields) {
        public End {
            fields = List.copyOf(fields);
        }
    }

    record Relationship(long id, End from, End to, String kind, String status, String name, Double confidence,
                        String evidenceJson, String decidedBy, Instant decidedAt, Instant foundAt,
                        Instant refreshedAt) {
    }

    /** What a refresh of inferred relationships changed. */
    record Refresh(int found, int added, int removed) {
    }

    /** A page of relationships and how many match in all. */
    record Page(List<Relationship> items, int total) {
    }

    /**
     * Replaces the declared relationships whose referring asset belongs to {@code sourceId} with
     * {@code keys}: new ones are added, ones no longer declared removed. A key matching an inferred
     * relationship turns it into a declared one. Keys whose assets are not recorded are skipped.
     *
     * @return the number of declared relationships the source now has
     */
    int saveDeclared(long sourceId, List<Declared> keys);

    /** Stores a column's sketch with its profile for run {@code runId}; older profiles drop theirs. */
    void saveSketch(String urn, String field, long runId, byte[] sketch);

    /**
     * Columns of current (not removed) assets, in every source, that could be either end of an
     * inferred relationship, at most {@code limit} of them: those of source {@code firstSourceId}
     * first, so a cap never cuts the source just scanned.
     */
    List<KeyColumn> keyColumns(long firstSourceId, int limit);

    /**
     * Runs {@code work} while holding the relationship lock, a PostgreSQL advisory lock shared by
     * every server process: one source's relationship refresh at a time, each seeing the last one's
     * committed result. Concurrent refreshes would otherwise lock the same rows in different
     * orders. Waits at most {@code wait} for the lock.
     *
     * @throws Busy if the lock is not free within {@code wait}; {@code work} has not run
     */
    <T> T serialised(java.util.function.Supplier<T> work, java.time.Duration wait);

    /** The relationship lock stayed taken for longer than the caller would wait. */
    final class Busy extends RuntimeException {
        @java.io.Serial
        private static final long serialVersionUID = 1L;

        public Busy(String message) {
            super(message);
        }
    }

    /** The latest sketch of each column that has one. */
    Map<FieldRef, byte[]> sketches(Collection<FieldRef> fields);

    /**
     * Refreshes the inferred relationships touching {@code sourceId} (either end in it) with what
     * inference found now: found ones are added or updated (confidence, evidence) keeping their
     * status; proposals not found again are removed; accepted and rejected ones stay unless a
     * field they name no longer exists. Pairs that are declared stay declared.
     */
    Refresh refreshInferred(long sourceId, List<Inferred> found);

    /**
     * Relationships whose two ends are both among {@code urns}, or (when {@code touching}) either end
     * is; at most {@code limit}, proposals and the most confident first.
     */
    List<Relationship> among(Collection<String> urns, boolean touching, int limit);

    /**
     * A page of relationships between current assets, filtered by asset (either end), source
     * (either end), status and kind (each null for any): proposals first, then the most confident.
     */
    Page list(String asset, String source, String status, String kind, int offset, int limit);

    Optional<Relationship> get(long id);

    /**
     * Records a person's decision on an inferred relationship: {@code accepted} or {@code rejected}.
     * Empty if there is no such relationship.
     *
     * @throws IllegalStateException if it is declared: the source decides those
     */
    Optional<Relationship> decide(long id, String status, String who);
}

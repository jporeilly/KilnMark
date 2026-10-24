package io.kilnmark.graph.store;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import io.kilnmark.graph.store.Records.Asset;
import io.kilnmark.graph.store.Records.AssetDetail;
import io.kilnmark.graph.store.Records.Field;
import io.kilnmark.graph.store.Records.NewSource;
import io.kilnmark.graph.store.Records.Run;
import io.kilnmark.graph.store.Records.SearchHit;
import io.kilnmark.graph.store.Records.Source;

/** Where Kilnmark keeps what it finds: sources, runs, assets, fields and profiles. */
public interface GraphStore {

    Source addSource(NewSource source);

    /** Adds every source in one transaction: all of them, or none. */
    List<Source> addSources(List<NewSource> sources);

    Optional<Source> source(String name);

    List<Source> sources();

    /** The encrypted password of a source, if it has one. */
    Optional<byte[]> sourceSecret(String name);

    /**
     * Changes a source's display name, settings and (when the change says so) secret. Its name,
     * part of every asset URN, never changes.
     *
     * @return the source as it now is; empty if there is no source {@code id}
     * @throws SourceChangedException when the secret is kept but the settings it depends on changed
     *                                meanwhile ({@link Records.SourceChange#keepSecretIf()})
     */
    Optional<Source> updateSource(long id, Records.SourceChange change);

    /**
     * Creates and updates sources in one transaction: all of them, or none.
     *
     * @param updates by source id
     */
    List<Source> saveSources(List<NewSource> creates, Map<Long, Records.SourceChange> updates);

    /**
     * Deletes a source and everything recorded about it (runs, jobs, assets, fields, profiles and
     * graph nodes) in one transaction.
     *
     * @return what was removed; empty if there is no source {@code id}
     * @throws SourceBusyException when a scan of it is queued or running
     */
    Optional<Records.Deleted> deleteSource(long id);

    /** How many assets each source has recorded (including ones no longer found), by source id. */
    Map<Long, Integer> assetCounts();

    /**
     * For each source with files, how many of its current files have each extension (lower case,
     * {@code (none)} without one), by source id.
     */
    Map<Long, Map<String, Integer>> fileFormats();

    /** Whether a scan of the source is queued or running. */
    boolean hasActiveRun(long sourceId);

    /** A source's latest runs, newest first, with their jobs' attempts and errors. */
    List<Records.RunHistory> runHistory(String sourceName, int limit);

    long queueRun(long sourceId, String kind);

    void startRun(long runId);

    void finishRun(long runId, String status, int assetsSeen, int assetsFailed, long rowsRead, String detail);

    Optional<Run> run(long runId);

    List<Run> runs(String sourceName, int limit);

    /** Records an asset seen by a run, replacing its fields with {@code fields}. */
    void saveAsset(long sourceId, long runId, Asset asset, List<Field> fields);

    /** Marks the source's assets that run {@code runId} did not see as removed. */
    int markUnseenRemoved(long sourceId, long runId);

    void saveRowCount(String urn, long rows, boolean complete, Instant at);

    void saveProfile(String urn, String field, long runId, String statsJson, Instant at);

    List<SearchHit> search(String query, int limit);

    Optional<AssetDetail> asset(String urn);

    /** Someone changed the source between reading it and saving a change to it. */
    final class SourceChangedException extends RuntimeException {
        @java.io.Serial
        private static final long serialVersionUID = 1L;

        public SourceChangedException(String message) {
            super(message);
        }
    }

    /** The source cannot be changed that way while a scan of it is queued or running. */
    final class SourceBusyException extends RuntimeException {
        @java.io.Serial
        private static final long serialVersionUID = 1L;

        public SourceBusyException(String message) {
            super(message);
        }
    }
}

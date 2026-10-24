package io.kilnmark.graph.store;

import java.time.Instant;
import java.util.List;
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
}

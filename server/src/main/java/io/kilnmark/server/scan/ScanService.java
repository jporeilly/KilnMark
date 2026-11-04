package io.kilnmark.server.scan;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import org.jboss.logging.Logger;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.kilnmark.graph.Urn;
import io.kilnmark.graph.store.GraphStore;
import io.kilnmark.graph.store.Records;
import io.kilnmark.kiln.MetadataEvent;
import io.kilnmark.kiln.Pipeline;
import io.kilnmark.kiln.RunResult;
import io.kilnmark.kiln.Runner;
import io.kilnmark.kiln.NodeSpec;
import io.kilnmark.kiln.connect.AssetInfo;
import io.kilnmark.kiln.connect.CatalogInfo;
import io.kilnmark.kiln.connect.Connector;
import io.kilnmark.kiln.connect.ConnectorException;
import io.kilnmark.kiln.connect.Connectors;
import io.kilnmark.kiln.connect.SourceConfig;
import io.kilnmark.kiln.plugins.profile.ProfileNode;
import io.kilnmark.server.KilnmarkConfig;
import io.kilnmark.server.SecretBox;
import io.kilnmark.server.jobs.JobQueue;
import io.kilnmark.server.relations.RelationshipService;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * Scans a data source through its connector: reads its catalogue, records every asset with its
 * fields, then reads each one through the Kiln engine (the connector's reader, then profile) and
 * stores the row count and column profiles. Assets are profiled a few at a time; one failing does
 * not stop the scan.
 *
 * <p>{@link #request} (api role) only queues the scan: a run and a job in the PostgreSQL queue. Any
 * process with the worker role picks the job up and calls {@link #execute}.
 */
@ApplicationScoped
public class ScanService {

    private static final Logger LOG = Logger.getLogger(ScanService.class);

    private final GraphStore store;
    private final SecretBox secrets;
    private final KilnmarkConfig config;
    private final ObjectMapper json;
    private final Connectors connectors;
    private final JobQueue jobs;
    private final RelationshipService relationships;
    private final io.kilnmark.server.sources.HostPolicy hosts;

    /** Reads documents' text (ADR 0017); injected here so the constructor stays as other streams know it. */
    @jakarta.inject.Inject
    io.kilnmark.server.documents.DocumentScan documents;

    /** Data quality: sampling settings, profile versions and samples, and the evaluation after each scan. */
    @jakarta.inject.Inject
    io.kilnmark.server.quality.QualityService quality;

    public ScanService(GraphStore store, SecretBox secrets, KilnmarkConfig config, ObjectMapper json,
                       Connectors connectors, JobQueue jobs, RelationshipService relationships) {
        this.store = store;
        this.secrets = secrets;
        this.config = config;
        this.json = json;
        this.connectors = connectors;
        this.jobs = jobs;
        this.relationships = relationships;
        this.hosts = io.kilnmark.server.sources.HostPolicy.of(config);
    }

    /** Queues a scan of {@code source} for a worker to run, and returns the queued run. */
    public Records.Run request(Records.Source source) {
        long runId = jobs.queueScan(source.id());
        return store.run(runId).orElseThrow();
    }

    private static final Set<String> UNFINISHED = Set.of("queued", "running");

    /** What {@link #execute} did. */
    public enum Outcome {
        /** The scan ran and its run has its final status. */
        FINISHED,
        /** Nothing to do: the run or its source is gone, or the run had already finished. */
        SKIPPED,
        /** Stopped part-way because it was asked to; the run is left for the caller to requeue. */
        CANCELLED,
        /**
         * Not started: a source it uses has no free place (ADR 0040). The run stays queued, and the
         * caller puts its job back for a few seconds without using an attempt.
         */
        WAITING
    }

    /** {@code note} says why a scan was skipped; null otherwise. */
    public record Result(Outcome outcome, String note) {
    }

    /** Thrown inside a profiling pipeline to end it once the scan is stopped. */
    private static final class Stopped extends RuntimeException {
        @java.io.Serial
        private static final long serialVersionUID = 1L;

        Stopped() {
            super("The scan was stopped", null, false, false);
        }
    }

    /**
     * Runs a queued scan; called by a worker holding the source's scan lock. Stops at the next
     * asset or profile once {@code cancelled} turns true, without interrupting threads that may be
     * inside a database call.
     */
    public Result execute(long runId, BooleanSupplier cancelled) {
        Optional<Records.Run> run = store.run(runId);
        if (run.isEmpty()) {
            return new Result(Outcome.SKIPPED, "Run " + runId + " no longer exists: its source was removed");
        }
        if (!UNFINISHED.contains(run.get().status())) {
            return new Result(Outcome.SKIPPED, "Run " + runId + " had already finished (" + run.get().status() + ")");
        }
        Optional<Records.Source> source = store.source(run.get().source());
        if (source.isEmpty()) {
            return new Result(Outcome.SKIPPED, "Run " + runId + "'s source no longer exists");
        }
        boolean finished = scan(source.get(), runId, cancelled);
        if (finished) {
            afterScan(source.get(), runId, cancelled);
        }
        return new Result(finished ? Outcome.FINISHED : Outcome.CANCELLED, null);
    }

    // After a scan (ADR 0016): observers of ScanFinished embed assets for search by meaning, and can
    // add other follow-up work. Field injection keeps the constructor as it is.
    @jakarta.inject.Inject
    jakarta.enterprise.event.Event<ScanFinished> scanFinished;

    private void afterScan(Records.Source source, long runId, BooleanSupplier cancelled) {
        if (scanFinished == null) {
            return;
        }
        try {
            String status = store.run(runId).map(Records.Run::status).orElse("failed");
            scanFinished.fire(new ScanFinished(source.id(), source.name(), runId, status, cancelled));
        } catch (RuntimeException e) {
            LOG.warnf(e, "Work after scan %d of %s failed; the scan itself is complete", runId, source.name());
        }
    }

    /**
     * Scans a source for run {@code runId}.
     *
     * @return true when the run has its final status; false when {@code cancelled} stopped it first
     */
    boolean scan(Records.Source source, long runId, BooleanSupplier cancelled) {
        store.startRun(runId);
        AtomicInteger seen = new AtomicInteger();
        AtomicInteger failed = new AtomicInteger();
        AtomicLong rows = new AtomicLong();
        List<String> problems = java.util.Collections.synchronizedList(new ArrayList<>());
        List<Thread> work = new ArrayList<>();
        AtomicBoolean halt = new AtomicBoolean();
        BooleanSupplier stop = () -> halt.get() || cancelled.getAsBoolean();
        String status = "failed";
        String detail;
        Connector ending = null; // documents (ADR 0017): told when the scan ends, however it ends
        SourceConfig endingConfig = null;
        try {
            Connector connector = connectors.get(source.kind()).orElseThrow(() -> new ConnectorException(
                "This server has no connector for sources of kind '" + source.kind() + "'"));
            String secret = store.sourceSecret(source.name()).map(secrets::open).orElse(null);
            SourceConfig config = new SourceConfig(source.name(), source.kind(), source.settings(), secret);
            // The allow-list as it is now, not as it was when the source was saved.
            String refused = hosts.refusal(connector, config);
            if (refused != null) {
                throw new ConnectorException(refused);
            }
            ending = connector;
            endingConfig = config;
            // Containers and include/exclude patterns, applied to every connector's catalogue alike;
            // relationships are kept only between assets that remain.
            CatalogInfo found = connector.catalogWithRelationships(config);
            // Assets inside others (a workbook's sheets) follow their parent: patterns match the
            // parent's path, so a sheet is kept exactly when its workbook is.
            List<AssetInfo> top = found.assets().stream().filter(a -> a.hints().get("parent") == null).toList();
            List<AssetInfo> keptTop = io.kilnmark.kiln.connect.AssetFilter.of(connector, config)
                .apply(connector, config, top);
            java.util.Set<String> parents = new java.util.HashSet<>();
            keptTop.forEach(a -> parents.add(a.path()));
            List<AssetInfo> assets = found.assets().stream().filter(a -> {
                String parent = a.hints().get("parent");
                return parents.contains(parent == null ? a.path() : parent);
            }).toList();
            java.util.Set<String> kept = new java.util.HashSet<>();
            assets.forEach(a -> kept.add(a.path()));
            CatalogInfo catalog = new CatalogInfo(assets, found.relationships().stream()
                .filter(r -> kept.contains(r.fromPath()) && kept.contains(r.toPath())).toList(),
                found.warnings(), found.relationshipsRead());
            Semaphore slots = new Semaphore(Math.max(1, this.config.scan().parallelTables()));
            for (AssetInfo a : assets) {
                if (stop.getAsBoolean()) {
                    break;
                }
                String urn = new Urn(source.name(), a.path()).toString();
                seen.incrementAndGet();
                try {
                    store.saveAsset(source.id(), runId, asset(urn, a), fields(a));
                    documents.recorded(source.name(), runId, urn, a); // documents (ADR 0017): a sheet's workbook
                } catch (RuntimeException ex) {
                    // one asset the database refuses (a name it cannot hold) must not end the scan
                    failed.incrementAndGet();
                    problems.add(a.path() + ": could not be recorded (" + ex.getMessage() + ")");
                    continue;
                }
                if (!a.readable()) {
                    continue; // recorded, not read: a document, for example
                }
                slots.acquire();
                if (stop.getAsBoolean()) {
                    slots.release();
                    break;
                }
                work.add(Thread.ofVirtual().name("scan-" + runId + "-" + a.path()).start(() -> {
                    try {
                        RunResult r = documents.handles(a)
                            ? documents.read(connector, config, a, urn, source.id(), runId, stop)
                            : profile(connector, config, a, urn, runId, stop, source);
                        rows.addAndGet(r.nodes().get(0).rowsOut());
                        if (!r.succeeded()) {
                            failed.incrementAndGet();
                            problems.add(a.path() + ": " + r.error());
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } catch (RuntimeException e) {
                        failed.incrementAndGet();
                        problems.add(a.path() + ": " + e.getMessage());
                    } finally {
                        slots.release();
                    }
                }));
            }
            joinAll(work);
            if (cancelled.getAsBoolean()) {
                return false; // nothing final written: the worker puts the scan back in the queue
            }
            int removed = store.markUnseenRemoved(source.id(), runId);
            quality.afterScan(source, runId);
            status = failed.get() == 0 ? "succeeded" : failed.get() < seen.get() ? "partial" : "failed";
            detail = summary(seen.get(), failed.get(), removed, problems) + relate(source, catalog);
            LOG.infof("Scan %d of %s: %s", runId, source.name(), detail);
        } catch (ConnectorException e) {
            detail = e.getMessage();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            detail = "The server stopped during the scan. Start the scan again.";
        } catch (RuntimeException e) {
            LOG.errorf(e, "Scan %d of %s failed", runId, source.name());
            detail = "Scan failed: " + e.getMessage();
        } finally {
            halt.set(true); // every exit: the assets still being profiled stop at their next event
            joinAll(work);
            if (ending != null) {
                try {
                    ending.finished(endingConfig); // documents (ADR 0017): shared copies of remote workbooks
                } catch (RuntimeException e) {
                    LOG.warnf("Connector clean-up after scan %d of %s failed: %s", runId, source.name(), e.getMessage());
                }
            }
        }
        // A database call with the interrupt flag set may be refused, so clear it for the final write.
        boolean interrupted = Thread.interrupted();
        try {
            store.finishRun(runId, status, seen.get(), failed.get(), rows.get(), detail);
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
        return true;
    }

    /** Waits for every thread, even if this one is interrupted meanwhile (the flag is kept). */
    private static void joinAll(List<Thread> work) {
        boolean interrupted = false;
        for (Thread t : work) {
            while (t.isAlive()) {
                try {
                    t.join();
                } catch (InterruptedException e) {
                    interrupted = true;
                }
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private RunResult profile(Connector connector, SourceConfig config, AssetInfo a, String urn, long runId,
                              BooleanSupplier stop, Records.Source source) throws InterruptedException {
        long limit = a.bounded() ? this.config.scan().viewRowLimit() : 0;
        NodeSpec read = connector.reader(config, a, new Connector.ReadOptions(urn, limit, 8192));
        Map<String, String> profiling = new java.util.HashMap<>(quality.profileSettings(source, connector, a, urn));
        profiling.put("asset-urn", urn);
        // Relationships: keep the distinct-value sketch of key-like fields only.
        profiling.put(ProfileNode.SKETCH_FIELDS, RelationshipService.sketchFields(a));
        Pipeline p = new Pipeline("scan-" + runId + "-" + a.path(), List.of(
            read, new NodeSpec("profile", new ProfileNode(), profiling)));
        return new Runner(this.config.scan().memoryLimitMb() << 20, 4).run(p, event -> {
            record(event, runId, stop);
            quality.observe(event, runId, source);
        });
    }

    /** Stores what a pipeline found; once the scan is stopped, ends the pipeline instead. */
    private void record(MetadataEvent e, long runId, BooleanSupplier stop) {
        if (stop.getAsBoolean()) {
            throw new Stopped();
        }
        recordEvent(e, runId);
    }

    /**
     * Stores a row count or column profile an engine node reported, under run {@code runId}: the
     * scan's own path, also used by pipelines to record what their writes wrote. The asset must
     * already be recorded ({@code saveAsset}).
     */
    public void recordEvent(MetadataEvent e, long runId) {
        switch (e.kind()) {
            case ROW_COUNT -> store.saveRowCount(e.assetUrn(), io.kilnmark.server.quality.QualityService.reportedRows(
                e.attributes()),
                Boolean.parseBoolean(e.attributes().get("complete")), e.observedAt());
            case PROFILE -> {
                store.saveProfile(e.assetUrn(), e.attributes().get("field"), runId, profileJson(e), e.observedAt());
                relationships.saveSketch(e, runId);
            }
            default -> { }
        }
    }

    private static final java.util.Set<String> COUNTS = java.util.Set.of("rows", "nulls", "distinct", "true",
        "min_length", "max_length");

    /** Profile attributes as a JSON object: counts as numbers, value lists as arrays, the rest as text. */
    public String profileJson(MetadataEvent e) {
        ObjectNode o = json.createObjectNode();
        for (var a : e.attributes().entrySet()) {
            String k = a.getKey();
            String v = a.getValue();
            if (k.equals("field") || RelationshipService.storedApart(k)) {
                continue;
            }
            try {
                if (COUNTS.contains(k)) {
                    o.put(k, Long.parseLong(v));
                } else if (k.equals("distinct_exact")) {
                    o.put(k, Boolean.parseBoolean(v));
                } else if (v.startsWith("[")) {
                    o.set(k, json.readTree(v));
                } else {
                    o.put(k, v);
                }
            } catch (Exception ex) {
                o.put(k, v);
            }
        }
        return o.toString();
    }

    /**
     * Records the source's declared relationships and refreshes inferred ones; for the run's
     * summary. A failure here never fails the scan: what was catalogued and profiled stays.
     */
    private String relate(Records.Source source, CatalogInfo catalog) {
        String warnings = catalog.warnings().isEmpty() ? "" : ". " + String.join("; ", catalog.warnings());
        try {
            RelationshipService.Outcome r = relationships.afterScan(source, catalog.relationships(),
                catalog.relationshipsRead());
            if (r.skipped() != null) {
                return warnings + ". " + r.skipped();
            }
            return warnings + (r.proposed() > 0 ? ". " + r.proposed()
                + (r.proposed() == 1 ? " relationship" : " relationships") + " proposed for review" : "");
        } catch (RuntimeException e) {
            LOG.errorf(e, "Relationship discovery for %s failed", source.name());
            return warnings + ". Relationship discovery failed: " + e.getMessage();
        }
    }

    private static Records.Asset asset(String urn, AssetInfo a) {
        return new Records.Asset(urn, a.kind(), a.container() == null ? "" : a.container(),
            a.name() == null ? a.path() : a.name(), a.description(), a.primaryKey());
    }

    private static List<Records.Field> fields(AssetInfo a) {
        return a.fields().stream().map(f -> new Records.Field(f.name(), f.position(), f.sourceType(), f.kind(),
            f.nullable(), f.description())).toList();
    }

    private static String summary(int seen, int failed, int removed, List<String> problems) {
        StringBuilder b = new StringBuilder();
        b.append(seen).append(seen == 1 ? " asset" : " assets").append(" scanned");
        if (failed > 0) {
            b.append(", ").append(failed).append(" could not be profiled");
        }
        if (removed > 0) {
            b.append(", ").append(removed).append(" no longer found");
        }
        if (!problems.isEmpty()) {
            b.append(". ").append(String.join("; ", problems.subList(0, Math.min(5, problems.size()))));
        }
        return b.toString();
    }
}

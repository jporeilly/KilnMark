package io.kilnmark.server.scan;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Semaphore;
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
import io.kilnmark.kiln.StepSpec;
import io.kilnmark.kiln.connect.AssetInfo;
import io.kilnmark.kiln.connect.Connector;
import io.kilnmark.kiln.connect.ConnectorException;
import io.kilnmark.kiln.connect.Connectors;
import io.kilnmark.kiln.connect.SourceConfig;
import io.kilnmark.kiln.plugins.profile.ProfileStep;
import io.kilnmark.server.KilnmarkConfig;
import io.kilnmark.server.SecretBox;
import io.kilnmark.server.jobs.JobQueue;
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

    public ScanService(GraphStore store, SecretBox secrets, KilnmarkConfig config, ObjectMapper json,
                       Connectors connectors, JobQueue jobs) {
        this.store = store;
        this.secrets = secrets;
        this.config = config;
        this.json = json;
        this.connectors = connectors;
        this.jobs = jobs;
    }

    /** Queues a scan of {@code source} for a worker to run, and returns the queued run. */
    public Records.Run request(Records.Source source) {
        long runId = jobs.queueScan(source.id());
        return store.run(runId).orElseThrow();
    }

    private static final Set<String> UNFINISHED = Set.of("queued", "running");

    /**
     * Runs a queued scan; called by a worker holding the run's lock. Does nothing, and returns a
     * reason, if the run no longer exists or has already finished.
     */
    public Optional<String> execute(long runId) {
        Optional<Records.Run> run = store.run(runId);
        if (run.isEmpty()) {
            return Optional.of("Run " + runId + " no longer exists: its source was removed");
        }
        if (!UNFINISHED.contains(run.get().status())) {
            return Optional.of("Run " + runId + " had already finished (" + run.get().status() + ")");
        }
        Optional<Records.Source> source = store.source(run.get().source());
        if (source.isEmpty()) {
            return Optional.of("Run " + runId + "'s source no longer exists");
        }
        scan(source.get(), runId);
        return Optional.empty();
    }

    void scan(Records.Source source, long runId) {
        store.startRun(runId);
        AtomicInteger seen = new AtomicInteger();
        AtomicInteger failed = new AtomicInteger();
        AtomicLong rows = new AtomicLong();
        List<String> problems = java.util.Collections.synchronizedList(new ArrayList<>());
        List<Thread> work = new ArrayList<>();
        try {
            Connector connector = connectors.get(source.kind()).orElseThrow(() -> new ConnectorException(
                "This server has no connector for sources of kind '" + source.kind() + "'"));
            String secret = store.sourceSecret(source.name()).map(secrets::open).orElse(null);
            SourceConfig config = new SourceConfig(source.name(), source.kind(), source.settings(), secret);
            List<AssetInfo> assets = connector.catalog(config);
            Semaphore slots = new Semaphore(Math.max(1, this.config.scan().parallelTables()));
            for (AssetInfo a : assets) {
                String urn = new Urn(source.name(), a.path()).toString();
                store.saveAsset(source.id(), runId, asset(urn, a), fields(a));
                seen.incrementAndGet();
                if (!a.readable()) {
                    continue; // recorded, not read: a document, for example
                }
                slots.acquire();
                work.add(Thread.ofVirtual().name("scan-" + runId + "-" + a.path()).start(() -> {
                    try {
                        RunResult r = profile(connector, config, a, urn, runId);
                        rows.addAndGet(r.steps().get(0).rowsOut());
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
            for (Thread t : work) {
                t.join();
            }
            int removed = store.markUnseenRemoved(source.id(), runId);
            String status = failed.get() == 0 ? "succeeded" : failed.get() < seen.get() ? "partial" : "failed";
            String detail = summary(seen.get(), failed.get(), removed, problems);
            store.finishRun(runId, status, seen.get(), failed.get(), rows.get(), detail);
            LOG.infof("Scan %d of %s: %s", runId, source.name(), detail);
        } catch (ConnectorException e) {
            store.finishRun(runId, "failed", seen.get(), failed.get(), rows.get(), e.getMessage());
        } catch (InterruptedException e) {
            stopAll(work);
            // Record the outcome first: a database call with the interrupt flag set may be refused.
            store.finishRun(runId, "failed", seen.get(), failed.get(), rows.get(),
                "The server stopped during the scan. Start the scan again.");
            Thread.currentThread().interrupt();
        } catch (RuntimeException e) {
            LOG.errorf(e, "Scan %d of %s failed", runId, source.name());
            store.finishRun(runId, "failed", seen.get(), failed.get(), rows.get(), "Scan failed: " + e.getMessage());
        }
    }

    /** Stops the assets still being profiled, and waits a little for them to let go of the run. */
    private static void stopAll(List<Thread> work) {
        work.forEach(Thread::interrupt);
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(10);
        for (Thread t : work) {
            long left = deadline - System.nanoTime();
            if (left <= 0) {
                break;
            }
            try {
                t.join(java.time.Duration.ofNanos(left));
            } catch (InterruptedException e) {
                break; // already stopping; the caller restores the flag
            }
        }
    }

    private RunResult profile(Connector connector, SourceConfig config, AssetInfo a, String urn, long runId)
        throws InterruptedException {
        long limit = a.bounded() ? this.config.scan().viewRowLimit() : 0;
        StepSpec read = connector.reader(config, a, new Connector.ReadOptions(urn, limit, 8192));
        Pipeline p = new Pipeline("scan-" + runId + "-" + a.path(), List.of(
            read, new StepSpec("profile", new ProfileStep(), Map.of("asset-urn", urn))));
        return new Runner(this.config.scan().memoryLimitMb() << 20, 4).run(p, event -> record(event, runId));
    }

    private void record(MetadataEvent e, long runId) {
        switch (e.kind()) {
            case ROW_COUNT -> store.saveRowCount(e.assetUrn(), Long.parseLong(e.attributes().get("rows")),
                Boolean.parseBoolean(e.attributes().get("complete")), e.observedAt());
            case PROFILE -> store.saveProfile(e.assetUrn(), e.attributes().get("field"), runId, profileJson(e),
                e.observedAt());
            default -> { }
        }
    }

    private static final java.util.Set<String> COUNTS = java.util.Set.of("rows", "nulls", "distinct", "true",
        "min_length", "max_length");

    /** Profile attributes as a JSON object: counts as numbers, value lists as arrays, the rest as text. */
    String profileJson(MetadataEvent e) {
        ObjectNode o = json.createObjectNode();
        for (var a : e.attributes().entrySet()) {
            String k = a.getKey();
            String v = a.getValue();
            if (k.equals("field")) {
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

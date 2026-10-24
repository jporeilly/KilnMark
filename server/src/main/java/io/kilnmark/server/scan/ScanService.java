package io.kilnmark.server.scan;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
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
import io.kilnmark.kiln.plugins.jdbc.JdbcCatalog;
import io.kilnmark.kiln.plugins.jdbc.JdbcTableInput;
import io.kilnmark.kiln.plugins.profile.ProfileStep;
import io.kilnmark.server.KilnmarkConfig;
import io.kilnmark.server.Role;
import io.kilnmark.server.SecretBox;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * Scans a data source: reads its catalogue, records every table and view with its fields, then
 * reads each one through the Kiln engine (table input, then profile) and stores the row count and
 * column profiles. Tables are profiled a few at a time; one table failing does not stop the scan.
 *
 * <p>For v0.0.2 scans run in this process when it has the worker role. The PostgreSQL job queue
 * (plan task 13) replaces this so any worker can pick a scan up.
 */
@ApplicationScoped
public class ScanService {

    private static final Logger LOG = Logger.getLogger(ScanService.class);

    private final GraphStore store;
    private final SecretBox secrets;
    private final KilnmarkConfig config;
    private final ObjectMapper json;
    private final ExecutorService scans = Executors.newVirtualThreadPerTaskExecutor();

    public ScanService(GraphStore store, SecretBox secrets, KilnmarkConfig config, ObjectMapper json) {
        this.store = store;
        this.secrets = secrets;
        this.config = config;
        this.json = json;
    }

    @PreDestroy
    void stop() {
        scans.shutdownNow();
    }

    /** Queues a scan of {@code source} and, on a worker, starts it. */
    public Records.Run request(Records.Source source) {
        long runId = store.queueRun(source.id(), "scan");
        if (config.roles().contains(Role.WORKER)) {
            scans.submit(() -> scan(source, runId));
        }
        return store.run(runId).orElseThrow();
    }

    void scan(Records.Source source, long runId) {
        store.startRun(runId);
        AtomicInteger seen = new AtomicInteger();
        AtomicInteger failed = new AtomicInteger();
        AtomicLong rows = new AtomicLong();
        List<String> problems = java.util.Collections.synchronizedList(new ArrayList<>());
        try {
            String password = store.sourceSecret(source.name()).map(secrets::open).orElse(null);
            List<JdbcCatalog.Table> tables;
            try (Connection c = DriverManager.getConnection(source.jdbcUrl(), source.username(), password)) {
                tables = JdbcCatalog.read(c, source.schemas());
            }
            Semaphore slots = new Semaphore(Math.max(1, config.scan().parallelTables()));
            List<Thread> work = new ArrayList<>();
            for (JdbcCatalog.Table t : tables) {
                String urn = new Urn(source.name(), t.path()).toString();
                store.saveAsset(source.id(), runId, asset(urn, t), fields(t));
                seen.incrementAndGet();
                slots.acquire();
                work.add(Thread.ofVirtual().name("scan-" + runId + "-" + t.path()).start(() -> {
                    try {
                        RunResult r = profile(source, password, t, urn, runId);
                        rows.addAndGet(r.steps().get(0).rowsOut());
                        if (!r.succeeded()) {
                            failed.incrementAndGet();
                            problems.add(t.path() + ": " + r.error());
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } catch (RuntimeException e) {
                        failed.incrementAndGet();
                        problems.add(t.path() + ": " + e.getMessage());
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
        } catch (SQLException e) {
            store.finishRun(runId, "failed", seen.get(), failed.get(), rows.get(),
                "Cannot read the catalogue of " + source.name() + ": " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            store.finishRun(runId, "failed", seen.get(), failed.get(), rows.get(), "The server stopped during the scan");
        } catch (RuntimeException e) {
            LOG.errorf(e, "Scan %d of %s failed", runId, source.name());
            store.finishRun(runId, "failed", seen.get(), failed.get(), rows.get(), "Scan failed: " + e.getMessage());
        }
    }

    private RunResult profile(Records.Source source, String password, JdbcCatalog.Table t, String urn, long runId)
        throws InterruptedException {
        boolean view = !t.type().equals("table") && !t.type().equals("partitioned table");
        long limit = view ? config.scan().viewRowLimit() : 0;
        java.util.HashMap<String, String> input = new java.util.HashMap<>(Map.of(
            "url", source.jdbcUrl(), "schema", t.schema(), "table", t.name(), "asset-urn", urn,
            "row-limit", Long.toString(limit)));
        if (source.username() != null) {
            input.put("user", source.username());
        }
        if (password != null) {
            input.put("password", password);
        }
        Pipeline p = new Pipeline("scan-" + runId + "-" + t.path(), List.of(
            new StepSpec("read", new JdbcTableInput(), input),
            new StepSpec("profile", new ProfileStep(), Map.of("asset-urn", urn))));
        return new Runner(config.scan().memoryLimitMb() << 20, 4).run(p, event -> record(event, runId));
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

    private static Records.Asset asset(String urn, JdbcCatalog.Table t) {
        return new Records.Asset(urn, t.type(), t.schema(), t.name(), t.comment(), t.primaryKey());
    }

    private static List<Records.Field> fields(JdbcCatalog.Table t) {
        return t.columns().stream().map(c -> new Records.Field(c.name(), c.position(), c.sourceType(),
            c.kind().name().toLowerCase(Locale.ROOT), c.nullable(), c.comment())).toList();
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

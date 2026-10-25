package io.kilnmark.server.api;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Semaphore;

import org.jboss.logging.Logger;

import io.kilnmark.kiln.connect.Connector;
import io.kilnmark.kiln.connect.SourceConfig;
import io.kilnmark.server.Db;
import io.kilnmark.server.jobs.JobQueue;
import io.kilnmark.server.sources.SourceChecks;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * The work a bulk import does after it answers: test each source's connection and queue a scan of
 * each, a few at a time, recording every row's progress in {@code import_batch_row} for the web UI
 * to poll. A batch runs in the api process that took the import; if that process stops, the batch
 * is reported as stopped once it has been silent for {@link #SILENT_AFTER}.
 */
@ApplicationScoped
public class ImportBatches {

    private static final Logger LOG = Logger.getLogger(ImportBatches.class);

    /** Rows of one batch tested at the same time. */
    static final int PARALLEL = 2;
    /** A running batch that has not moved for this long was left by a server that stopped. */
    static final Duration SILENT_AFTER = Duration.ofMinutes(5);
    /** Batches are kept this long. */
    static final Duration KEEP = Duration.ofDays(7);

    /** One source to test or scan. {@code sourceId} is null in a dry run, where nothing was saved. */
    record Item(int line, String name, Connector connector, SourceConfig config, Long sourceId) {
    }

    private final Db db;
    private final SourceChecks checks;
    private final JobQueue jobs;
    /** Notifications: a failed connection test of a saved source is told. */
    @jakarta.inject.Inject
    io.kilnmark.server.notify.NotificationPublisher notifications;

    public ImportBatches(Db db, SourceChecks checks, JobQueue jobs) {
        this.db = db;
        this.checks = checks;
        this.jobs = jobs;
    }

    /** Records a batch and starts its work in the background; returns its id at once. */
    long start(String createdBy, boolean dryRun, boolean test, boolean scan, List<Item> items) {
        boolean scanning = scan && !dryRun;
        long id = db.tx(c -> {
            try (PreparedStatement ps = c.prepareStatement("DELETE FROM kilnmark.import_batch "
                + "WHERE created_at < now() - make_interval(secs => ?)")) {
                ps.setDouble(1, KEEP.toSeconds());
                ps.executeUpdate();
            }
            long batch;
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO kilnmark.import_batch (created_by, dry_run, "
                + "test, scan) VALUES (?, ?, ?, ?) RETURNING id")) {
                ps.setString(1, createdBy);
                ps.setBoolean(2, dryRun);
                ps.setBoolean(3, test);
                ps.setBoolean(4, scanning);
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    batch = rs.getLong(1);
                }
            }
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO kilnmark.import_batch_row (batch_id, line, "
                + "name, test, scan) VALUES (?, ?, ?, ?, ?)")) {
                for (Item i : items) {
                    ps.setLong(1, batch);
                    ps.setInt(2, i.line());
                    ps.setString(3, i.name());
                    ps.setString(4, test ? "waiting" : "skipped");
                    ps.setString(5, scanning && i.sourceId() != null ? "waiting" : "skipped");
                    ps.addBatch();
                }
                ps.executeBatch();
            }
            return batch;
        });
        Thread.ofVirtual().name("kilnmark-import-" + id).start(() -> run(id, test, scanning, items));
        return id;
    }

    private void run(long batch, boolean test, boolean scan, List<Item> items) {
        Semaphore slots = new Semaphore(PARALLEL);
        List<Thread> threads = new ArrayList<>();
        try {
            for (Item item : items) {
                slots.acquire();
                threads.add(Thread.ofVirtual().name("kilnmark-import-" + batch + "-" + item.line()).start(() -> {
                    try {
                        process(batch, item, test, scan);
                    } catch (RuntimeException e) {
                        LOG.warnf("Import batch %d, line %d: %s", batch, item.line(), e.getMessage());
                    } finally {
                        slots.release();
                    }
                }));
            }
            for (Thread t : threads) {
                t.join();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            try {
                db.tx(c -> {
                    try (PreparedStatement ps = c.prepareStatement("UPDATE kilnmark.import_batch SET status = "
                        + "'finished', finished_at = now(), updated_at = now() WHERE id = ?")) {
                        ps.setLong(1, batch);
                        ps.executeUpdate();
                    }
                    return null;
                });
            } catch (RuntimeException e) {
                LOG.warnf("Could not mark import batch %d finished: %s", batch, e.getMessage());
            }
        }
    }

    private void process(long batch, Item item, boolean test, boolean scan) {
        boolean ok = true;
        if (test) {
            update(batch, item.line(), "test = 'testing'", null, null);
            SourceChecks.TestResult r = checks.testInBackground(item.connector(), item.config());
            ok = r.ok();
            update(batch, item.line(), ok ? "test = 'ok'" : "test = 'failed', problem = ?", ok ? null : r.problem(),
                null);
            if (item.sourceId() != null && notifications != null) {
                notifications.connectionTested(item.name(), ok, r.problem());
            }
        }
        if (scan && item.sourceId() != null) {
            if (!ok) {
                update(batch, item.line(), "scan = 'skipped'", null, null);
                return;
            }
            try {
                long run = jobs.queueScan(item.sourceId());
                update(batch, item.line(), "scan = 'queued', run_id = ?", null, run);
            } catch (RuntimeException e) {
                LOG.warnf("Import batch %d could not queue a scan of %s: %s", batch, item.name(), e.getMessage());
                update(batch, item.line(), "scan = 'failed', problem = ?", "The scan could not be queued. Start it "
                    + "from the source's page.", null);
            }
        }
    }

    private void update(long batch, int line, String set, String text, Long number) {
        db.tx(c -> {
            try (PreparedStatement ps = c.prepareStatement("UPDATE kilnmark.import_batch_row SET " + set
                + " WHERE batch_id = ? AND line = ?")) {
                int i = 1;
                if (text != null) {
                    ps.setString(i++, text);
                }
                if (number != null) {
                    ps.setLong(i++, number);
                }
                ps.setLong(i++, batch);
                ps.setInt(i, line);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = c.prepareStatement(
                "UPDATE kilnmark.import_batch SET updated_at = now() WHERE id = ?")) {
                ps.setLong(1, batch);
                ps.executeUpdate();
            }
            return null;
        });
    }

    /** A batch with each row's progress. */
    Optional<Views.ImportBatch> get(long id) {
        return db.tx(c -> {
            Views.ImportBatch head;
            try (PreparedStatement ps = c.prepareStatement("SELECT status, dry_run, test, scan, created_at, finished_at, "
                + "updated_at < now() - make_interval(secs => ?) FROM kilnmark.import_batch WHERE id = ?")) {
                ps.setDouble(1, SILENT_AFTER.toSeconds());
                ps.setLong(2, id);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        return Optional.empty();
                    }
                    String status = rs.getString(1);
                    if (status.equals("running") && rs.getBoolean(7)) {
                        status = "stopped"; // the server running it stopped; the import can simply be run again
                    }
                    head = new Views.ImportBatch(id, status, rs.getBoolean(2), rs.getBoolean(3), rs.getBoolean(4),
                        instant(rs.getTimestamp(5)), instant(rs.getTimestamp(6)), List.of());
                }
            }
            List<Views.ImportBatchRow> rows = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement("SELECT line, name, test, problem, scan, run_id "
                + "FROM kilnmark.import_batch_row WHERE batch_id = ? ORDER BY line")) {
                ps.setLong(1, id);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        long run = rs.getLong(6);
                        Long runId = rs.wasNull() ? null : run;
                        rows.add(new Views.ImportBatchRow(rs.getInt(1), rs.getString(2), rs.getString(3),
                            rs.getString(4), rs.getString(5), runId));
                    }
                }
            }
            return Optional.of(new Views.ImportBatch(head.id(), head.status(), head.dryRun(), head.test(), head.scan(),
                head.createdAt(), head.finishedAt(), rows));
        });
    }

    private static Instant instant(java.sql.Timestamp t) {
        return t == null ? null : t.toInstant();
    }
}

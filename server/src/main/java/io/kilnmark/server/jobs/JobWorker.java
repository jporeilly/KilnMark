package io.kilnmark.server.jobs;

import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.jboss.logging.Logger;
import org.postgresql.PGConnection;
import org.postgresql.PGNotification;

import io.kilnmark.server.Db;
import io.kilnmark.server.KilnmarkConfig;
import io.kilnmark.server.Role;
import io.kilnmark.server.scan.ScanService;
import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;

/**
 * The worker role: pulls jobs from {@link JobQueue} and runs them, a few at a time, on virtual
 * threads. It wakes on PostgreSQL {@code LISTEN/NOTIFY} and also polls, so a missed notification
 * only delays a job. Running jobs send heartbeats; on start, and every so often, jobs left by a
 * worker that stopped are recovered. On shutdown it stops claiming, lets running jobs finish for
 * {@code kilnmark.worker.shutdown-timeout}, then asks them to stop (at most {@value #CANCEL_GRACE_SECONDS}
 * seconds more) and puts them back in the queue without counting the attempt.
 *
 * <p>A process without the worker role never starts this, so it never runs a job.
 */
@ApplicationScoped
public class JobWorker {

    private static final Logger LOG = Logger.getLogger(JobWorker.class);
    private static final Duration RECOVER_EVERY = Duration.ofSeconds(30);
    private static final Duration LOCKED_RETRY = Duration.ofSeconds(30);
    /** How long a run waiting for a free place on a source stays back in the queue (ADR 0040). */
    static final Duration PLACE_RETRY = Duration.ofSeconds(5);
    /** After the shutdown timeout, how long cancelled scans get to wind down. */
    static final int CANCEL_GRACE_SECONDS = 15;

    /**
     * A job this worker is running. Setting {@code cancelled} stops its scan at the next asset or
     * profile event; threads are never interrupted, since one inside a JDBC call would break its
     * pooled connection.
     */
    private record Execution(JobQueue.Job job, JobQueue.ScanLease lease, AtomicBoolean cancelled) {
    }

    private final JobQueue queue;
    private final ScanService scans;
    private final Db db;
    private final KilnmarkConfig config;
    private final Map<Long, Execution> running = new ConcurrentHashMap<>();
    /** Runs jobs of kind {@value io.kilnmark.server.quality.QualityService#JOB_KIND}. */
    @jakarta.inject.Inject
    io.kilnmark.server.quality.QualityService quality;
    /** Notifications: a scan that fails for good here (its attempts used up) is told, as a finished scan is. */
    @jakarta.inject.Inject
    io.kilnmark.server.notify.NotificationPublisher notifications;
    @jakarta.inject.Inject
    io.kilnmark.graph.store.GraphStore graph;
    /** Jobs whose lease was lost: another worker may now own them, so this one must not touch them. */
    private final Map<Long, Boolean> lost = new ConcurrentHashMap<>();
    private final Semaphore wake = new Semaphore(0);
    private volatile boolean stopping;
    /** Set once running jobs are done with: the heartbeat and listener threads then end. */
    private volatile boolean halted;
    private String workerId;
    private Semaphore slots;
    private Thread poller;
    private Thread listener;

    public JobWorker(JobQueue queue, ScanService scans, Db db, KilnmarkConfig config) {
        this.queue = queue;
        this.scans = scans;
        this.db = db;
        this.config = config;
    }

    void onStart(@Observes StartupEvent event) {
        if (config.roles().contains(Role.WORKER)) {
            start();
        }
    }

    void onStop(@Observes ShutdownEvent event) {
        stop();
    }

    /** Whether this process is taking jobs: started, not stopping, and its poller alive. */
    public boolean running() {
        Thread p = poller;
        return p != null && p.isAlive() && !stopping;
    }

    /** This worker's name in the queue's {@code locked_by}: host, process id and a random suffix. */
    public String id() {
        return workerId;
    }

    synchronized void start() {
        if (poller != null) {
            return;
        }
        String host = Optional.ofNullable(System.getenv("HOSTNAME")).filter(h -> !h.isBlank()).orElse("worker");
        byte[] suffix = new byte[3];
        new SecureRandom().nextBytes(suffix);
        workerId = host + ":" + ProcessHandle.current().pid() + ":" + HexFormat.of().formatHex(suffix);
        int concurrency = Math.max(1, config.worker().concurrency());
        slots = new Semaphore(concurrency);
        int flowCount = Math.max(1, config.worker().flows());
        flowSlots = new Semaphore(flowCount);
        flowLeases = queue.flowLeases();
        recover();
        Thread.ofVirtual().name("kilnmark-job-heartbeat").start(this::beat);
        listener = Thread.ofVirtual().name("kilnmark-job-listener").start(this::listen);
        poller = Thread.ofVirtual().name("kilnmark-job-poller").start(this::poll);
        LOG.infof("Worker %s started: up to %d jobs and %d flows at a time", workerId, concurrency, flowCount);
    }

    /**
     * Requeues or fails jobs left by a stopped worker, then marks failed the flow, pipeline and
     * quality runs whose jobs have failed: on start and every {@link #RECOVER_EVERY}, never on a
     * request.
     */
    void recover() {
        try {
            JobQueue.Recovery r = queue.recoverStale(config.worker().staleAfter(), config.runs().lostAfter());
            if (r.requeued() + r.failed() > 0) {
                LOG.infof("Recovered jobs left by a stopped worker: %d queued again, %d failed", r.requeued(),
                    r.failed());
            }
            if (flows != null) {
                flows.failOrphanedRuns();
            }
            if (pipelines != null) {
                pipelines.failOrphanedRuns();
            }
            if (quality != null) {
                quality.failOrphanedRuns();
            }
        } catch (RuntimeException e) {
            LOG.warnf("Could not check for jobs left by a stopped worker: %s. Will try again shortly.", e.getMessage());
        }
    }

    /** Flows this worker watches at once ({@code kilnmark.worker.flows}); they hold no slot (see {@link #poll}). */
    private Semaphore flowSlots;
    /** The locks of the flows this worker watches, all on one connection (docs/design/flows.md, section 4). */
    private JobQueue.FlowLeases flowLeases;

    private void poll() {
        Duration every = config.worker().pollInterval();
        while (!stopping) {
            try {
                slots.acquire();
                Optional<JobQueue.Job> job;
                try {
                    job = stopping ? Optional.empty() : queue.claim(workerId);
                } catch (RuntimeException e) {
                    slots.release();
                    LOG.warnf("Could not read the job queue: %s. Trying again in %s.", e.getMessage(), every);
                    sleep(every);
                    continue;
                }
                if (job.isPresent()) {
                    JobQueue.Job j = job.get();
                    // A flow only watches the pipeline runs it queued, and those need slots of their own: it
                    // must not hold one, or flows could fill every slot and wait for runs that never start.
                    boolean light = io.kilnmark.server.flows.FlowStore.JOB_KIND.equals(j.kind());
                    if (light) {
                        slots.release();
                        // A few flows at a time: each takes connections now and then as it looks (their locks
                        // share one), and the pipeline runs they wait for need the rest.
                        if (!flowSlots.tryAcquire()) {
                            queue.release(j, Duration.ofSeconds(5), null);
                            continue;
                        }
                    }
                    Thread.ofVirtual().name("kilnmark-job-" + j.id()).start(() -> {
                        try {
                            execute(j);
                        } finally {
                            if (light) {
                                flowSlots.release();
                            } else {
                                slots.release();
                            }
                        }
                    });
                } else {
                    slots.release();
                    if (wake.tryAcquire(every.toMillis(), TimeUnit.MILLISECONDS)) {
                        wake.drainPermits();
                    }
                }
            } catch (InterruptedException e) {
                return;
            }
        }
    }

    /** Runs jobs of kind {@value io.kilnmark.server.pipelines.PipelineStore#JOB_KIND}. */
    @jakarta.inject.Inject
    io.kilnmark.server.pipelines.PipelineService pipelines;

    /** Runs jobs of kind {@value io.kilnmark.server.flows.FlowStore#JOB_KIND}. */
    @jakarta.inject.Inject
    io.kilnmark.server.flows.FlowService flows;

    /** Runs one claimed job while holding its source's scan lock (a pipeline job: its own lock). */
    void execute(JobQueue.Job job) {
        if (io.kilnmark.server.pipelines.PipelineStore.JOB_KIND.equals(job.kind())) {
            executeLocked(job, "Pipeline", queue::lockPipelineJob,
                (id, lease, stop) -> pipelines.execute(id, lease, stop));
            return;
        }
        if (io.kilnmark.server.flows.FlowStore.JOB_KIND.equals(job.kind())) {
            executeLocked(job, "Flow", flowLeases::lock, (id, lease, stop) -> flows.execute(id, lease, stop));
            return;
        }
        boolean qualityJob = io.kilnmark.server.quality.QualityService.JOB_KIND.equals(job.kind()) && job.sourceId() >= 0;
        if (!qualityJob && (!"scan".equals(job.kind()) || job.runId() < 0 || job.sourceId() < 0)) {
            queue.retryOrFail(new JobQueue.Job(job.id(), job.kind(), job.runId(), job.sourceId(), job.maxAttempts(),
                job.maxAttempts(), job.workerId()), "This server cannot run jobs of kind '" + job.kind()
                + "' with this payload");
            return;
        }
        Optional<JobQueue.ScanLease> lease;
        try {
            lease = queue.lockSource(job.sourceId());
        } catch (RuntimeException e) {
            queue.retryOrFail(job, e.getMessage());
            return;
        }
        if (lease.isEmpty()) {
            LOG.infof("Source %d is being scanned by another worker; job %d goes back in the queue", job.sourceId(),
                job.id());
            queue.release(job, LOCKED_RETRY, null);
            return;
        }
        AtomicBoolean cancelled = new AtomicBoolean();
        running.put(job.id(), new Execution(job, lease.get(), cancelled));
        try {
            ScanService.Result result = qualityJob ? quality.execute(job.id(), cancelled::get)
                : scans.execute(job.runId(), cancelled::get);
            if (lost.remove(job.id()) != null) {
                LOG.warnf("Job %d lost its lock while running; another worker may own it now", job.id());
            } else if (result.outcome() == ScanService.Outcome.CANCELLED) {
                // Stopped by shutdown: not the job's fault, so the attempt is not counted.
                queue.release(job, Duration.ZERO,
                    "Queued again: the worker running this scan was stopped. Another worker will run it.");
            } else {
                queue.complete(job, result.note());
            }
        } catch (RuntimeException e) {
            LOG.errorf(e, "Job %d (run %d) failed", job.id(), job.runId());
            if (lost.remove(job.id()) == null && !queue.retryOrFail(job, String.valueOf(e.getMessage())) && !qualityJob) {
                scanFailedForGood(job);
            }
        } finally {
            running.remove(job.id());
            lease.get().close();
            lost.remove(job.id());
        }
    }

    /** What a job that holds a lock of its own runs: the job's id, its lease, and whether the worker is stopping. */
    @FunctionalInterface
    private interface LockedRun {
        ScanService.Result run(long jobId, JobQueue.ScanLease lease, java.util.function.BooleanSupplier stop);
    }

    /**
     * Runs a pipeline or flow job while holding its own lock, taken by {@code lock} (no source lock:
     * runs of a pipeline may read sources being scanned; a flow's lock is on the connection its
     * worker's flows share). A pipeline or flow job is tried as many times as its run may be (ADR
     * 0053), so an exception that escapes it is queued again while tries are left; a run stopped by
     * shutdown ends its try as lost, tried again only when its run: options allow. A run waiting for
     * a free place on a source, or a flow handed over as its worker stops
     * ({@link ScanService.Outcome#WAITING}), goes back in the queue without using that attempt.
     */
    private void executeLocked(JobQueue.Job job, String what,
                               java.util.function.LongFunction<Optional<JobQueue.ScanLease>> lock, LockedRun body) {
        Optional<JobQueue.ScanLease> lease;
        try {
            lease = lock.apply(job.id());
        } catch (RuntimeException e) {
            queue.retryOrFail(job, e.getMessage());
            return;
        }
        if (lease.isEmpty()) {
            queue.release(job, LOCKED_RETRY, null);
            return;
        }
        AtomicBoolean cancelled = new AtomicBoolean();
        running.put(job.id(), new Execution(job, lease.get(), cancelled));
        boolean waiting = false;
        try {
            ScanService.Result result = body.run(job.id(), lease.get(), cancelled::get);
            if (lost.remove(job.id()) != null) {
                LOG.warnf("%s job %d lost its lock while running", what, job.id());
            } else if (result.outcome() == ScanService.Outcome.WAITING) {
                waiting = true; // put back once the lease, and with it the job's lock, is given up
            } else {
                queue.complete(job, result.note());
            }
        } catch (RuntimeException e) {
            // Not the exception's text or trace: a source's secret could be in it.
            LOG.errorf("%s job %d failed with %s", what, job.id(), e.getClass().getName());
            if (lost.remove(job.id()) == null) {
                queue.retryOrFail(job, "The " + what.toLowerCase(Locale.ROOT) + " job failed (" + e.getClass().getSimpleName()
                    + "); the run says why");
            }
        } finally {
            running.remove(job.id());
            lease.get().close();
            lost.remove(job.id());
        }
        if (waiting) {
            // A source it uses is full: back in the queue for a few seconds, without using its one attempt.
            try {
                queue.release(job, PLACE_RETRY, null);
            } catch (RuntimeException e) {
                // Still running in the queue, never started: recovery puts it back (JobQueue.recoverStale).
                LOG.warnf("Could not put waiting %s job %d back in the queue; recovery will", what.toLowerCase(Locale.ROOT),
                    job.id());
            }
        }
    }

    /** Tells of a scan whose last attempt failed with an error; never throws. */
    private void scanFailedForGood(JobQueue.Job job) {
        try {
            if (notifications != null && graph != null) {
                graph.run(job.runId()).ifPresent(r -> notifications.scanFinished(r.source(), r.id(), r.status(), r.detail()));
            }
        } catch (RuntimeException e) {
            LOG.warnf("Could not tell of failed scan %d: %s", job.runId(), e.getMessage());
        }
    }

    /** Waits for notifications that a job was queued, and wakes the poller. */
    private void listen() {
        while (!halted && !stopping) {
            try (Connection c = db.connection()) {
                c.setAutoCommit(true);
                try (Statement s = c.createStatement()) {
                    s.execute("LISTEN " + JobQueue.CHANNEL);
                }
                try {
                    PGConnection pg = c.unwrap(PGConnection.class);
                    while (!stopping) {
                        PGNotification[] n = pg.getNotifications(1000);
                        if (n != null && n.length > 0) {
                            wake.release();
                        }
                    }
                } finally {
                    try (Statement s = c.createStatement()) {
                        s.execute("UNLISTEN *"); // the connection goes back to the pool
                    } catch (SQLException e) {
                        LOG.debugf(e, "UNLISTEN failed");
                    }
                }
            } catch (SQLException | RuntimeException e) {
                if (stopping) {
                    return;
                }
                LOG.warnf("Not receiving job notifications (%s); polling every %s until it reconnects",
                    e.getMessage(), config.worker().pollInterval());
                if (!sleep(config.worker().pollInterval())) {
                    return;
                }
            }
        }
    }

    /** Heartbeats for running jobs, and a recovery pass now and then. */
    private void beat() {
        long lastRecovery = System.nanoTime();
        while (!halted) {
            for (Execution e : List.copyOf(running.values())) {
                if (!e.lease().beat(e.job().id(), workerId)) {
                    LOG.warnf("Job %d (run %d) is no longer held by this worker; stopping it", e.job().id(),
                        e.job().runId());
                    lost.put(e.job().id(), Boolean.TRUE);
                    e.cancelled().set(true);
                }
            }
            if (!stopping && System.nanoTime() - lastRecovery > RECOVER_EVERY.toNanos()) {
                recover();
                lastRecovery = System.nanoTime();
            }
            if (!sleep(config.worker().heartbeatInterval())) {
                return;
            }
        }
    }

    /** Stops claiming, lets running jobs finish up to the shutdown timeout, then stops and requeues them. */
    synchronized void stop() {
        if (poller == null || stopping) {
            return;
        }
        // No interrupts for the poller, listener or heartbeat: interrupting a virtual thread inside a
        // JDBC call closes its socket, and the broken connection would go back to the pool.
        stopping = true;
        wake.release();
        long deadline = System.nanoTime() + config.worker().shutdownTimeout().toNanos();
        if (!running.isEmpty()) {
            LOG.infof("Waiting up to %s for %d running jobs to finish", config.worker().shutdownTimeout(),
                running.size());
        }
        while (!running.isEmpty() && System.nanoTime() < deadline) {
            if (!sleep(Duration.ofMillis(100))) {
                break;
            }
        }
        if (!running.isEmpty()) {
            LOG.infof("Stopping %d jobs that did not finish in time; they go back in the queue", running.size());
            running.values().forEach(e -> e.cancelled().set(true));
            long grace = System.nanoTime() + TimeUnit.SECONDS.toNanos(CANCEL_GRACE_SECONDS);
            while (!running.isEmpty() && System.nanoTime() < grace) {
                if (!sleep(Duration.ofMillis(100))) {
                    break;
                }
            }
        }
        halted = true;
        try {
            listener.join(Duration.ofSeconds(3)); // it checks every second; let it hand its connection back
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        LOG.infof("Worker %s stopped", workerId);
    }

    private static boolean sleep(Duration d) {
        try {
            Thread.sleep(d);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}

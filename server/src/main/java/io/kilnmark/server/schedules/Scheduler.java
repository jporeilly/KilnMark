package io.kilnmark.server.schedules;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

import org.jboss.logging.Logger;

import io.kilnmark.server.KilnmarkConfig;
import io.kilnmark.server.Role;
import io.kilnmark.server.api.AlreadyRunning;
import io.kilnmark.server.api.ApiError;
import io.kilnmark.server.auth.AccessRole;
import io.kilnmark.server.flows.FlowService;
import io.kilnmark.server.pipelines.PipelineService;
import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.ws.rs.WebApplicationException;

/**
 * Fires schedules (ADR 0046, docs/design/schedules.md) on processes with the scheduler role, every
 * {@code kilnmark.schedules.poll-interval} (30 seconds by default).
 *
 * <p>At most once: one short transaction claims the due schedules (locking each, skipping those
 * another scheduler holds) and moves each one's next fire past now; only after it commits is each
 * run queued, as Run now queues it, and what happened recorded. Two schedulers never fire one
 * schedule twice; a crash between the claim and the queue loses that fire, which is the safer
 * failure for loads of data. A fire due longer ago than {@code kilnmark.schedules.catch-up} (the
 * server was not running) is recorded as missed and not run; one due more recently runs once.
 */
@ApplicationScoped
public class Scheduler {

    private static final Logger LOG = Logger.getLogger(Scheduler.class);

    /** The most schedules claimed in one transaction; a tick that fills it looks again at once. */
    static final int BATCH = 100;

    /** A run was queued. */
    public static final String STARTED = "started";
    /** The pipeline or flow already had a run queued or running. */
    public static final String SKIPPED = "skipped: a run was still going";
    /** The fire was due too long ago: the server was not running. */
    public static final String MISSED = "missed: the server was not running";
    /** Before the reason a run could not be queued. */
    public static final String FAILED = "failed: ";

    private final ScheduleStore store;
    private final ScheduleService schedules;
    private final PipelineService pipelines;
    private final FlowService flows;
    private final KilnmarkConfig config;
    private volatile Thread thread;

    public Scheduler(ScheduleStore store, ScheduleService schedules, PipelineService pipelines, FlowService flows,
                     KilnmarkConfig config) {
        this.store = store;
        this.schedules = schedules;
        this.pipelines = pipelines;
        this.flows = flows;
        this.config = config;
    }

    void onStart(@Observes StartupEvent event) {
        if (!config.roles().contains(Role.SCHEDULER)) {
            return;
        }
        Duration every = config.schedules().pollInterval();
        if (config.schedules().catchUp().compareTo(every) < 0) {
            LOG.warnf("kilnmark.schedules.catch-up (%s) is shorter than kilnmark.schedules.poll-interval (%s): "
                + "most fires will be found too late and recorded as missed", config.schedules().catchUp(), every);
        }
        thread = Thread.ofVirtual().name("kilnmark-scheduler").start(() -> {
            try {
                while (!Thread.currentThread().isInterrupted()) {
                    Thread.sleep(every);
                    int claimed;
                    do {
                        claimed = fire(Instant.now()); // a full batch: more may be due
                    } while (claimed == BATCH);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
    }

    void onStop(@Observes ShutdownEvent event) {
        Thread t = thread;
        if (t != null) {
            t.interrupt();
        }
    }

    /** As {@link #tick}, never throwing: a database that cannot be reached is logged and tried next time. */
    private int fire(Instant now) {
        try {
            return tick(now);
        } catch (RuntimeException e) {
            LOG.warnf("Could not look for schedules that are due: %s", e.getMessage());
            return 0;
        }
    }

    /**
     * Fires every schedule due at {@code now} (up to {@value #BATCH}): claims them, then queues each
     * run and records its outcome. Returns how many were claimed, missed ones included.
     */
    int tick(Instant now) {
        Duration catchUp = config.schedules().catchUp();
        int[] claimed = {0};
        List<ScheduleStore.Due> due = store.claimDue(now, BATCH, s -> {
            claimed[0]++;
            return plan(s, now, catchUp);
        });
        for (ScheduleStore.Due d : due) {
            run(d.schedule());
        }
        return claimed[0];
    }

    /** When a due schedule fires next, from now, and whether this fire was missed. */
    static ScheduleStore.Claim plan(ScheduleStore.Schedule s, Instant now, Duration catchUp) {
        Instant next;
        try {
            next = Cron.parse(s.cron()).next(now, ZoneId.of(s.zone()));
        } catch (RuntimeException e) {
            // saved before a rule it breaks, or a zone this Java no longer knows: it stops, saying why
            return new ScheduleStore.Claim(null, FAILED + e.getMessage());
        }
        boolean missed = s.nextAt() != null && s.nextAt().isBefore(now.minus(catchUp));
        return new ScheduleStore.Claim(next, missed ? MISSED : null);
    }

    /** Queues one claimed schedule's run, as Run now would, and records what happened. */
    private void run(ScheduleStore.Schedule s) {
        String by = ScheduleService.requestedBy(s);
        String outcome;
        Long pipelineRun = null;
        Long flowRun = null;
        try {
            AccessRole role = AccessRole.parse(s.runAs());
            Map<String, Object> parameters = schedules.parameters(s);
            if (s.pipeline() != null) {
                pipelineRun = pipelines.queueRun(s.pipeline(), null, parameters, by, role).id();
            } else {
                flowRun = flows.queueRun(s.flow(), null, parameters, by, role).id();
            }
            outcome = STARTED;
        } catch (AlreadyRunning e) {
            outcome = SKIPPED;
        } catch (WebApplicationException e) {
            outcome = FAILED + (e.getResponse().getEntity() instanceof ApiError a ? a.error() : e.getMessage());
        } catch (IllegalArgumentException e) {
            outcome = FAILED + e.getMessage();
        } catch (RuntimeException e) {
            LOG.warnf(e, "Schedule %d of %s could not queue its run", s.id(), ScheduleService.target(s));
            outcome = FAILED + "the run could not be queued; the server's log says why";
        }
        LOG.infof("Schedule %d of %s: %s", s.id(), ScheduleService.target(s), outcome);
        try {
            store.recordOutcome(s.id(), outcome, pipelineRun, flowRun);
        } catch (RuntimeException e) {
            LOG.warnf("Could not record what schedule %d did (%s): %s", s.id(), outcome, e.getMessage());
        }
    }
}

package io.kilnmark.server.schedules;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.jboss.logging.Logger;

import io.kilnmark.server.KilnmarkConfig;
import io.kilnmark.server.Role;
import io.kilnmark.server.api.AlreadyRunning;
import io.kilnmark.server.api.ApiError;
import io.kilnmark.server.audit.AuditLog;
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
    /** When the loop last looked for due schedules, whether or not that worked; null before it starts. */
    private volatile Instant lastTick;

    /** Whether whoever set a schedule still holds its role. */
    @jakarta.inject.Inject
    Setters setters;

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
        lastTick = Instant.now();
        thread = Thread.ofVirtual().name("kilnmark-scheduler").start(() -> {
            try {
                while (!Thread.currentThread().isInterrupted()) {
                    Thread.sleep(every);
                    int claimed;
                    do {
                        claimed = fire(); // a full batch: more may be due
                    } while (claimed == BATCH);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
    }

    /**
     * Whether schedules are being looked at: true on a process without the scheduler role;
     * otherwise its loop is running and has looked within three poll intervals and a minute. The
     * readiness check reports it.
     */
    public boolean running() {
        if (!config.roles().contains(Role.SCHEDULER)) {
            return true;
        }
        Thread t = thread;
        Instant last = lastTick;
        Duration every = config.schedules().pollInterval();
        return t != null && t.isAlive() && last != null
            && last.isAfter(Instant.now().minus(every.multipliedBy(3)).minus(Duration.ofMinutes(1)));
    }

    void onStop(@Observes ShutdownEvent event) {
        Thread t = thread;
        if (t != null) {
            t.interrupt();
        }
    }

    /**
     * As {@link #tick()}, never throwing, whatever goes wrong: anything, an {@link Error} too, is
     * logged at ERROR and the loop looks again at the next poll, so one bad tick never stops the
     * scheduler for good.
     */
    private int fire() {
        try {
            return tick();
        } catch (Throwable e) {
            LOG.errorf("Could not look for schedules that are due (%s); looking again at the next poll",
                e.getClass().getSimpleName());
            LOG.debug("The scheduler's tick failed", e);
            return 0;
        } finally {
            lastTick = Instant.now();
        }
    }

    /** Fires every schedule due now by the database's clock; see {@link #tick(Instant)}. */
    int tick() {
        return tick(null);
    }

    /**
     * Fires every schedule due at {@code now} (up to {@value #BATCH}): claims them, then queues each
     * run and records its outcome. Returns how many were claimed, missed ones included. Now is the
     * database's clock when {@code now} is null, as it always is outside tests.
     */
    int tick(Instant now) {
        Duration catchUp = config.schedules().catchUp();
        int[] claimed = {0};
        List<ScheduleStore.Due> due = store.claimDue(now, BATCH, (s, at) -> {
            claimed[0]++;
            return plan(s, at, catchUp);
        });
        for (ScheduleStore.Due d : due) {
            run(d.schedule());
        }
        return claimed[0];
    }

    /**
     * When a due schedule fires next and whether this fire was missed. The next fire is worked out
     * from now, or from its last fire if that is later, so it can never come before one already
     * claimed.
     */
    static ScheduleStore.Claim plan(ScheduleStore.Schedule s, Instant now, Duration catchUp) {
        Instant next;
        try {
            next = Cron.parse(s.cron()).next(ScheduleStore.later(now, s.lastAt()), ZoneId.of(s.zone()));
        } catch (RuntimeException e) {
            // saved before a rule it breaks, or a zone this Java no longer knows: it stops, saying why
            return new ScheduleStore.Claim(null, FAILED + e.getMessage());
        }
        boolean missed = s.nextAt() != null && s.nextAt().isBefore(now.minus(catchUp));
        return new ScheduleStore.Claim(next, missed ? MISSED : null);
    }

    /**
     * Finishes one claimed fire, under the schedule's row lock (ScheduleStore.fire): whoever set it
     * must still hold its role, or nothing runs and it is paused, recorded in the audit log
     * (ADR 0046); then its run is queued, as Run now would, and the outcome recorded. A run already
     * queued or running is never cancelled by a pause.
     */
    private void run(ScheduleStore.Schedule claimed) {
        Optional<ScheduleStore.Fired> fired;
        try {
            fired = store.fire(claimed, this::decide);
        } catch (RuntimeException e) {
            LOG.warnf("Schedule %d of %s: could not finish its fire (%s)", claimed.id(),
                ScheduleService.target(claimed), e.getClass().getSimpleName());
            return;
        }
        fired.filter(f -> f.pauseReason() != null)
            .ifPresent(f -> schedules.paused(claimed, f.pauseReason(), AuditLog.Actor.server("scheduler")));
    }

    /** What one fire does, the schedule's row being locked: the setter's check, then the run. */
    private ScheduleStore.Fired decide(ScheduleStore.Schedule s) {
        Setters.Problem problem;
        try {
            problem = setters.problem(s);
        } catch (RuntimeException e) {
            // cannot tell (the database is unreachable): nothing runs, and the fire is lost, not paused
            LOG.warnf("Schedule %d of %s: could not check who set it (%s)", s.id(), ScheduleService.target(s),
                e.getClass().getSimpleName());
            return new ScheduleStore.Fired(FAILED + "Kilnmark could not check the role of whoever set it", null, null,
                null);
        }
        if (problem != null) {
            return new ScheduleStore.Fired(problem.outcome(), null, null, problem.reason());
        }
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
            // its message may quote the run's values: only its kind here, the rest at DEBUG
            LOG.warnf("Schedule %d of %s could not queue its run (%s)", s.id(), ScheduleService.target(s),
                e.getClass().getSimpleName());
            LOG.debug("Why the scheduled run could not be queued", e);
            outcome = FAILED + "the run could not be queued; the server's log says why";
        }
        // the outcome's reason can be worked out from the run's values: INFO says only what happened
        LOG.infof("Schedule %d of %s: %s", s.id(), ScheduleService.target(s), kind(outcome));
        LOG.debugf("Schedule %d: %s", s.id(), outcome);
        return new ScheduleStore.Fired(outcome, pipelineRun, flowRun, null);
    }

    /** An outcome's first word: started, skipped, missed or failed. */
    static String kind(String outcome) {
        int colon = outcome.indexOf(':');
        return colon < 0 ? outcome : outcome.substring(0, colon);
    }
}

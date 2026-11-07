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
 * run checked and queued, as Run now checks and queues it, and what happened recorded. The checks
 * (a version compiled, a flow's pipelines read) run with no lock held; the schedule's row is
 * locked again only to check its setter, queue the checked run and record the outcome. Two schedulers never fire one
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
     * How many times a fire checks its run again when the schedule was changed between the check and
     * taking its row; after that it checks under the row lock, as a change keeps coming.
     */
    static final int RECHECKS = 2;

    /** Called while a fire checks its run, with no lock held; tests hold a fire there. */
    private volatile Runnable checking = () -> { };

    /** Sets what is called while a fire checks its run (tests). */
    void whileChecking(Runnable hook) {
        checking = hook;
    }

    /**
     * Finishes one claimed fire. Its run is first checked as Run now checks it (a version compiled, a
     * flow's pipelines read), with no lock held, on the schedule as it is now. Then, under the
     * schedule's row lock (ScheduleStore.fire), held only for this: whoever set it must still hold its
     * role, or nothing runs and it is paused, recorded in the audit log (ADR 0046); then the checked
     * run is queued and the outcome recorded. If what the run was checked for (what it runs, its
     * values, its role) changed meanwhile, it is checked again. A run already queued or running is
     * never cancelled by a pause.
     */
    void run(ScheduleStore.Schedule claimed) {
        java.util.concurrent.atomic.AtomicReference<ScheduleStore.Fired> decided =
            new java.util.concurrent.atomic.AtomicReference<>();
        Optional<ScheduleStore.Fired> fired = Optional.empty();
        for (int attempt = 0; ; attempt++) {
            try {
                // after RECHECKS changes the check is made under the lock, so a fire always ends
                Checked checked = attempt <= RECHECKS ? store.find(claimed.id())
                    .filter(s -> startingAsClaimed(s, claimed)).map(this::check).orElse(null) : null;
                fired = store.fire(claimed, s -> {
                    if (checked != null && !checked.isFor(s)) {
                        throw new Changed();
                    }
                    ScheduleStore.Fired f = decide(s, checked);
                    decided.set(f);
                    return f;
                });
                break;
            } catch (Changed e) {
                // changed since the check: nothing was written, and the row is free again
            } catch (RuntimeException e) {
                LOG.warnf("Schedule %d of %s: could not finish its fire (%s)", claimed.id(),
                    ScheduleService.target(claimed), e.getClass().getSimpleName());
                ScheduleStore.Fired f = decided.get();
                if (f != null && f.pauseReason() == null) {
                    // the run may be queued already: say so, rather than leave it reading as a lost fire
                    retry(claimed.id(), f);
                }
                return;
            }
        }
        // the schedule as it is now, after any change saved before the fire took its row
        fired.filter(f -> f.pauseReason() != null)
            .ifPresent(f -> schedules.paused(f.schedule(), f.pauseReason(), AuditLog.Actor.server("scheduler")));
    }

    /** Whether {@code s} is still the fire {@code claimed} started: on, starting, running the same thing. */
    private static boolean startingAsClaimed(ScheduleStore.Schedule s, ScheduleStore.Schedule claimed) {
        return s.enabled() && ScheduleStore.STARTING.equals(s.lastOutcome())
            && java.util.Objects.equals(s.pipeline(), claimed.pipeline())
            && java.util.Objects.equals(s.flow(), claimed.flow());
    }

    /** The schedule changed between the check of its run and the fire taking its row. */
    private static final class Changed extends RuntimeException {
        @java.io.Serial
        private static final long serialVersionUID = 1L;

        Changed() {
            super("changed since it was checked", null, false, false);
        }
    }

    /**
     * A fire's run, checked for the schedule {@code as} it was: the checked run, or why it cannot run
     * (a version with problems, a value it refuses), turned into the outcome only if the fire gets
     * that far, so a fire that pauses instead says nothing of it.
     */
    private record Checked(ScheduleStore.Schedule as, Object run, RuntimeException refusal) {

        /** Whether {@code s} runs what was checked: the same pipeline or flow, values and role. */
        boolean isFor(ScheduleStore.Schedule s) {
            return java.util.Objects.equals(as.pipeline(), s.pipeline()) && java.util.Objects.equals(as.flow(), s.flow())
                && java.util.Objects.equals(as.parameters(), s.parameters())
                && java.util.Objects.equals(as.runAs(), s.runAs());
        }
    }

    /** Checks the run a fire of {@code s} would queue, as Run now would, holding no lock. */
    private Checked check(ScheduleStore.Schedule s) {
        checking.run();
        try {
            AccessRole role = AccessRole.parse(s.runAs());
            Map<String, Object> parameters = schedules.parameters(s);
            Object run = s.pipeline() != null ? pipelines.starter().check(s.pipeline(), null, parameters, role)
                : flows.starter().check(s.flow(), null, parameters, role);
            return new Checked(s, run, null);
        } catch (RuntimeException e) {
            return new Checked(s, null, e);
        }
    }

    /** The outcome of a run that could not be checked or queued: the server's message, as Run now shows it. */
    private static String refused(ScheduleStore.Schedule s, RuntimeException e) {
        if (e instanceof AlreadyRunning) {
            return SKIPPED;
        }
        if (e instanceof WebApplicationException w) {
            return FAILED + (w.getResponse().getEntity() instanceof ApiError a ? a.error() : w.getMessage());
        }
        if (e instanceof IllegalArgumentException) {
            return FAILED + e.getMessage();
        }
        // its message may quote the run's values: only its kind here, the rest at DEBUG
        LOG.warnf("Schedule %d of %s could not queue its run (%s)", s.id(), ScheduleService.target(s),
            e.getClass().getSimpleName());
        LOG.debug("Why the scheduled run could not be queued", e);
        return FAILED + "the run could not be queued; the server's log says why";
    }

    /** Writes a fire's outcome again, up to three times, after the fire could not write it. */
    private void retry(long id, ScheduleStore.Fired f) {
        for (int i = 0; i < 3; i++) {
            try {
                store.recordAfterFailure(id, f);
                return;
            } catch (RuntimeException e) {
                LOG.warnf("Schedule %d: could not record what its fire did (%s), try %d of 3", id,
                    e.getClass().getSimpleName(), i + 1);
            }
        }
    }

    /**
     * What one fire does, the schedule's row being locked: the setter's check, then the run, queued
     * from {@code checked} (checked before the lock, for this schedule as it is), or checked and
     * queued here when it is null.
     */
    private ScheduleStore.Fired decide(ScheduleStore.Schedule s, Checked checked) {
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
        Checked run = checked != null ? checked : check(s);
        String by = ScheduleService.requestedBy(s);
        String outcome = run.refusal() == null ? null : refused(s, run.refusal());
        Long pipelineRun = null;
        Long flowRun = null;
        if (outcome == null) {
            try {
                AccessRole role = AccessRole.parse(s.runAs());
                if (s.pipeline() != null) {
                    pipelineRun = pipelines.starter().queue((PipelineService.CheckedRun) run.run(), by, role);
                } else {
                    flowRun = flows.starter().queue((FlowService.CheckedRun) run.run(), by, role);
                }
                outcome = STARTED;
            } catch (RuntimeException e) {
                outcome = refused(s, e);
            }
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

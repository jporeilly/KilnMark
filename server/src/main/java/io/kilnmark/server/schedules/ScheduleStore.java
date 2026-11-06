package io.kilnmark.server.schedules;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.BiFunction;
import java.util.function.Function;

import io.kilnmark.server.Db;
import io.kilnmark.server.KilnmarkConfig;
import io.kilnmark.server.auth.AccessRole;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * The {@code schedule} table (V36): saving schedules, and claiming those that are due. Every time
 * a next fire is worked out from is the database's clock, never a process's own, and never earlier
 * than the schedule's last fire, so processes whose clocks differ cannot put back a fire already
 * claimed (ADR 0046).
 */
@ApplicationScoped
public class ScheduleStore {

    /** The longest outcome kept: a server message can be long. */
    static final int MAX_OUTCOME = 500;

    /** The outcome a claimed fire has while its run is being queued. */
    public static final String STARTING = "starting";

    /**
     * How {@link #STARTING} reads once it is older than {@link #staleAfter}: the process stopped
     * after the claim and before it queued the run, so that fire was lost.
     */
    public static final String UNQUEUED = "failed: the server stopped before the run was queued";

    /** The outcome when a claimed fire finds its schedule paused, changed or set to run something else. */
    public static final String CHANGED_WHILE_STARTING = "skipped: the schedule was changed while it was starting";

    /** The outcome when a change to a schedule's times, zone or pausing meets a fire that was due. */
    public static final String CHANGED_WHILE_DUE = "missed: changed while it was due";

    private final Db db;
    private final String columns;
    private final Duration staleAfter;

    public ScheduleStore(Db db, KilnmarkConfig config) {
        this.db = db;
        this.staleAfter = staleAfter(config.schedules().pollInterval());
        // the last column says whether a fire still "starting" has been so for too long
        this.columns = "id, pipeline, flow, cron, zone, parameters::text, run_as, enabled, description, created_by, "
            + "created_at, updated_by, updated_at, next_at, last_at, last_outcome, last_pipeline_run, last_flow_run, "
            + "(last_outcome = '" + STARTING + "' AND last_at < clock_timestamp() - make_interval(secs => "
            + staleAfter.toSeconds() + ")), set_by_token, set_by_person";
    }

    /** Three poll intervals, and at least five minutes: longer than any healthy claim takes to queue. */
    static Duration staleAfter(Duration poll) {
        Duration three = poll.multipliedBy(3);
        return three.compareTo(Duration.ofMinutes(5)) < 0 ? Duration.ofMinutes(5) : three;
    }

    /**
     * A schedule as stored; {@code parameters} is its JSON object as text. {@code setByToken} and
     * {@code setByPerson} say who last set it, as {@link Setter} does.
     */
    public record Schedule(long id, String pipeline, String flow, String cron, String zone, String parameters,
                           String runAs, boolean enabled, String description, String createdBy, Instant createdAt,
                           String updatedBy, Instant updatedAt, Instant nextAt, Instant lastAt, String lastOutcome,
                           Long lastPipelineRun, Long lastFlowRun, Long setByToken, String setByPerson) {
    }

    /**
     * Who sets a schedule: the name runs are requested by, and the identity whose role each fire
     * checks (ADR 0046): the API token they used, or, for a person signed in through the identity
     * provider, an opaque key for them ({@code OidcIdentityAugmentor.personRef}). Both null only
     * while sign-in is off.
     */
    public record Setter(String name, Long token, String person) {
    }

    /** The outcome when a fire finds that whoever set the schedule no longer holds its role. */
    public static final String PAUSED = "paused: the person who set it no longer has the role";

    /** What a schedule is set to: everything a person chooses. */
    public record Setting(String pipeline, String flow, String cron, String zone, String parameters, String runAs,
                          boolean enabled, String description) {
    }

    /**
     * What claiming a due schedule does: when it fires next (null: never again), and an outcome to
     * record now when it is not to run (null: run it). A fire that is to run records
     * {@link #STARTING} until its own outcome replaces it.
     */
    public record Claim(Instant nextAt, String outcome) {
    }

    /** A schedule claimed to run: as it was when claimed, with when the fire was due. */
    public record Due(Schedule schedule, Instant dueAt) {
    }

    /** Every schedule, or those of one pipeline or flow, by id. */
    public List<Schedule> list(String pipeline, String flow) {
        return db.tx(c -> {
            StringBuilder sql = new StringBuilder("SELECT " + columns + " FROM kilnmark.schedule WHERE true");
            if (pipeline != null) {
                sql.append(" AND pipeline = ?");
            }
            if (flow != null) {
                sql.append(" AND flow = ?");
            }
            sql.append(" ORDER BY id");
            try (PreparedStatement ps = c.prepareStatement(sql.toString())) {
                int i = 1;
                if (pipeline != null) {
                    ps.setString(i++, pipeline);
                }
                if (flow != null) {
                    ps.setString(i, flow);
                }
                return read(ps);
            }
        });
    }

    public Optional<Schedule> find(long id) {
        return db.tx(c -> find(c, id, false));
    }

    /**
     * Adds a schedule set by {@code by}. {@code next} gives its first fire after a time, which is
     * the database's clock now; it is not asked while the schedule is paused.
     */
    public Schedule insert(Setting s, Function<Instant, Instant> next, Setter by) {
        return db.tx(c -> {
            long id;
            Instant nextAt = s.enabled() ? next.apply(clock(c)) : null;
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO kilnmark.schedule (pipeline, flow, cron, zone, "
                + "parameters, run_as, enabled, description, created_by, updated_by, next_at, set_by_token, "
                + "set_by_person) VALUES (?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?, ?, ?, ?, ?) RETURNING id")) {
                int i = set(ps, s, 1);
                ps.setString(i++, by.name());
                ps.setString(i++, by.name());
                ps.setTimestamp(i++, Db.timestamp(nextAt));
                ps.setObject(i++, by.token(), Types.BIGINT);
                ps.setString(i, by.person());
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    id = rs.getLong(1);
                }
            }
            return find(c, id, false).orElseThrow();
        });
    }

    /**
     * Replaces what schedule {@code id} is set to; empty when there is none. The row is locked
     * first, so this never overlaps a claim.
     *
     * <ul>
     *   <li>When its times, zone and pausing are as they were, its next fire stays as it is, due or
     *       not: a change to anything else never moves or drops a fire.</li>
     *   <li>Otherwise {@code next} works the next fire out from the database's clock after the lock,
     *       or from the last fire if that is later. A fire that was due and not yet claimed is then
     *       recorded as {@link #CHANGED_WHILE_DUE}, so it is seen rather than silently dropped.</li>
     *   <li>When it runs something else now, its last outcome and run are cleared.</li>
     * </ul>
     */
    public Optional<Schedule> update(long id, Setting s, Function<Instant, Instant> next, Setter by) {
        return db.tx(c -> {
            Optional<Schedule> found = find(c, id, true);
            if (found.isEmpty()) {
                return Optional.empty();
            }
            Schedule old = found.get();
            Instant now = clock(c);
            boolean sameTarget = Objects.equals(old.pipeline(), s.pipeline()) && Objects.equals(old.flow(), s.flow());
            boolean sameTimes = old.cron().equals(s.cron()) && old.zone().equals(s.zone())
                && old.enabled() == s.enabled() && (old.nextAt() != null || !old.enabled());
            Instant nextAt;
            Instant lastAt = old.lastAt();
            String outcome = sameTarget ? old.lastOutcome() : null;
            Long pipelineRun = sameTarget ? old.lastPipelineRun() : null;
            Long flowRun = sameTarget ? old.lastFlowRun() : null;
            if (sameTimes) {
                nextAt = old.nextAt();
            } else {
                nextAt = s.enabled() ? next.apply(later(now, old.lastAt())) : null;
                if (old.enabled() && old.nextAt() != null && !old.nextAt().isAfter(now)) {
                    lastAt = now;
                    outcome = CHANGED_WHILE_DUE;
                    pipelineRun = null;
                    flowRun = null;
                }
            }
            try (PreparedStatement ps = c.prepareStatement("UPDATE kilnmark.schedule SET pipeline = ?, flow = ?, "
                + "cron = ?, zone = ?, parameters = ?::jsonb, run_as = ?, enabled = ?, description = ?, updated_by = ?, "
                + "set_by_token = ?, set_by_person = ?, updated_at = now(), next_at = ?, last_at = ?, "
                + "last_outcome = ?, last_pipeline_run = ?, last_flow_run = ? WHERE id = ?")) {
                int i = set(ps, s, 1);
                ps.setString(i++, by.name());
                ps.setObject(i++, by.token(), Types.BIGINT);
                ps.setString(i++, by.person());
                ps.setTimestamp(i++, Db.timestamp(nextAt));
                ps.setTimestamp(i++, Db.timestamp(lastAt));
                ps.setString(i++, outcome);
                ps.setObject(i++, pipelineRun, Types.BIGINT);
                ps.setObject(i++, flowRun, Types.BIGINT);
                ps.setLong(i, id);
                ps.executeUpdate();
            }
            return find(c, id, false);
        });
    }

    /** What a schedule runs: a pipeline or a flow, each named by a column of its own. */
    public enum Target {
        PIPELINE("pipeline"),
        FLOW("flow");

        private final String column;

        Target(String column) {
            this.column = column;
        }
    }

    /** A schedule deleted with its pipeline or flow (ADR 0046), as the audit log records it. */
    public record Gone(long id, String cron, String zone, String runAs, boolean enabled) {
    }

    /**
     * Locks the schedules of a pipeline or flow that is being deleted, in {@code c}'s transaction.
     * Call it before locking the pipeline's or flow's own row: a schedule's fire holds its row while
     * it queues a run, which locks the pipeline or flow, so the other order could deadlock
     * (docs/design/schedules.md, "Locks").
     */
    public static void lockFor(Connection c, Target target, String name) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
            "SELECT 1 FROM kilnmark.schedule WHERE " + target.column + " = ? ORDER BY id FOR UPDATE")) {
            ps.setString(1, name);
            ps.executeQuery().close();
        }
    }

    /**
     * Deletes the schedules of a pipeline or flow in {@code c}'s transaction, returning exactly those
     * it deleted, so each can be recorded: one added meanwhile is either deleted and returned here,
     * or not deleted at all.
     */
    public static List<Gone> deleteFor(Connection c, Target target, String name) throws SQLException {
        List<Gone> gone = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement("DELETE FROM kilnmark.schedule WHERE " + target.column
            + " = ? RETURNING id, cron, zone, run_as, enabled")) {
            ps.setString(1, name);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    gone.add(new Gone(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4),
                        rs.getBoolean(5)));
                }
            }
        }
        return gone;
    }

    /** Deletes schedule {@code id}; returns it as it was, or empty when there was none. */
    public Optional<Schedule> delete(long id) {
        return db.tx(c -> {
            Optional<Schedule> s = find(c, id, true);
            if (s.isPresent()) {
                try (PreparedStatement ps = c.prepareStatement("DELETE FROM kilnmark.schedule WHERE id = ?")) {
                    ps.setLong(1, id);
                    ps.executeUpdate();
                }
            }
            return s;
        });
    }

    /**
     * Claims up to {@code limit} enabled schedules due now, in one short transaction: each is locked
     * (those another scheduler holds are skipped), {@code plan} says when it fires next and whether
     * it runs, and that is written with {@code last_at} = now before the transaction commits.
     * Returns those to run. Now is the database's clock, or {@code at} when a test gives one. A
     * schedule is claimed once, however many schedulers look at the same time: the claim moves
     * {@code next_at} past now.
     *
     * @param plan given the schedule and now, says what the claim does
     */
    public List<Due> claimDue(Instant at, int limit, BiFunction<Schedule, Instant, Claim> plan) {
        return db.tx(c -> {
            Instant now = at != null ? at : clock(c);
            List<Schedule> due;
            try (PreparedStatement ps = c.prepareStatement("SELECT " + columns + " FROM kilnmark.schedule "
                + "WHERE enabled AND next_at <= ? ORDER BY next_at, id LIMIT ? FOR UPDATE SKIP LOCKED")) {
                ps.setTimestamp(1, Db.timestamp(now));
                ps.setInt(2, limit);
                due = read(ps);
            }
            List<Due> run = new ArrayList<>();
            for (Schedule s : due) {
                Claim claim = plan.apply(s, now);
                try (PreparedStatement ps = c.prepareStatement("UPDATE kilnmark.schedule SET next_at = ?, last_at = ?, "
                    + "last_outcome = ?, last_pipeline_run = NULL, last_flow_run = NULL WHERE id = ?")) {
                    ps.setTimestamp(1, Db.timestamp(claim.nextAt()));
                    ps.setTimestamp(2, Db.timestamp(now));
                    ps.setString(3, clip(claim.outcome() == null ? STARTING : claim.outcome()));
                    ps.setLong(4, s.id());
                    ps.executeUpdate();
                }
                if (claim.outcome() == null) {
                    run.add(new Due(s, s.nextAt()));
                }
            }
            return run;
        });
    }

    /**
     * What a fire did: its outcome and the run it started, if any; {@code pauseReason} when it was
     * not run because whoever set the schedule no longer holds its role, which pauses it.
     */
    public record Fired(String outcome, Long pipelineRun, Long flowRun, String pauseReason, Schedule schedule) {

        /** What a fire did with the schedule as it was when it ran. */
        public Fired(String outcome, Long pipelineRun, Long flowRun, String pauseReason) {
            this(outcome, pipelineRun, flowRun, pauseReason, null);
        }
    }

    /**
     * Finishes a claimed fire under the schedule's row lock, held from the check of its setter to
     * the outcome, so a token revoked or a schedule paused or changed meanwhile either comes first
     * (nothing runs) or waits until the run is queued and recorded. The row must still be on, still
     * {@link #STARTING} and run the same pipeline or flow; {@code work} gets it as it is now (its
     * setter may have changed) and returns what it did, which is written before the lock is let go:
     * a pause turns it off with no next fire. When the row is no longer so, nothing runs, and a row
     * left {@link #STARTING} says {@link #CHANGED_WHILE_STARTING}. Returns what was done, if anything.
     */
    public Optional<Fired> fire(Schedule claimed, Function<Schedule, Fired> work) {
        return db.tx(c -> {
            Optional<Schedule> current;
            try (PreparedStatement ps = c.prepareStatement("SELECT " + columns + " FROM kilnmark.schedule WHERE "
                + "id = ? AND enabled AND last_outcome = ? AND pipeline IS NOT DISTINCT FROM ? "
                + "AND flow IS NOT DISTINCT FROM ? FOR UPDATE")) {
                ps.setLong(1, claimed.id());
                ps.setString(2, STARTING);
                ps.setString(3, claimed.pipeline());
                ps.setString(4, claimed.flow());
                current = read(ps).stream().findFirst();
            }
            if (current.isEmpty()) {
                try (PreparedStatement ps = c.prepareStatement("UPDATE kilnmark.schedule SET last_outcome = ? "
                    + "WHERE id = ? AND last_outcome = ?")) {
                    ps.setString(1, CHANGED_WHILE_STARTING);
                    ps.setLong(2, claimed.id());
                    ps.setString(3, STARTING);
                    ps.executeUpdate();
                }
                return Optional.<Fired>empty();
            }
            Fired done = work.apply(current.get());
            Fired f = new Fired(done.outcome(), done.pipelineRun(), done.flowRun(), done.pauseReason(), current.get());
            String sql = f.pauseReason() != null
                ? "UPDATE kilnmark.schedule SET enabled = false, next_at = NULL, last_at = clock_timestamp(), "
                    + "last_outcome = ?, last_pipeline_run = ?, last_flow_run = ? WHERE id = ?"
                : "UPDATE kilnmark.schedule SET last_outcome = ?, last_pipeline_run = ?, last_flow_run = ? WHERE id = ?";
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                ps.setString(1, clip(f.outcome()));
                ps.setObject(2, f.pipelineRun(), Types.BIGINT);
                ps.setObject(3, f.flowRun(), Types.BIGINT);
                ps.setLong(4, claimed.id());
                ps.executeUpdate();
            }
            return Optional.of(f);
        });
    }

    /**
     * Pauses every schedule on that {@code token} or {@code person} set (give one) whose role is
     * above {@code role} (null: every one), at once: off, no next fire, outcome {@link #PAUSED}.
     * Returns those paused, as they were.
     */
    public List<Schedule> pauseSetBy(Long token, String person, AccessRole role) {
        return pauseSetBy(token, person, role, null);
    }

    /**
     * As {@link #pauseSetBy(Long, String, AccessRole)}, waiting at most {@code lockTimeout} for a
     * schedule's row (a fire in progress holds it); past that it throws and pauses nothing. Only the
     * rows that will pause are locked: those whose role is above {@code role}.
     */
    public List<Schedule> pauseSetBy(Long token, String person, AccessRole role, Duration lockTimeout) {
        List<String> above = new ArrayList<>();
        for (AccessRole r : List.of(AccessRole.STEWARD, AccessRole.DESIGNER)) {
            if (role == null || !role.atLeast(r)) {
                above.add(r.id());
            }
        }
        if (above.isEmpty()) {
            return List.of();
        }
        return db.tx(c -> {
            if (lockTimeout != null) {
                try (java.sql.Statement st = c.createStatement()) {
                    st.execute("SET LOCAL lock_timeout = '" + Math.max(1, lockTimeout.toMillis()) + "ms'");
                }
            }
            List<Schedule> found;
            try (PreparedStatement ps = c.prepareStatement("SELECT " + columns + " FROM kilnmark.schedule WHERE "
                + "enabled AND " + (token != null ? "set_by_token = ?" : "set_by_person = ?")
                + " AND run_as = ANY (?) ORDER BY id FOR UPDATE")) {
                if (token != null) {
                    ps.setLong(1, token);
                } else {
                    ps.setString(1, person);
                }
                ps.setArray(2, c.createArrayOf("text", above.toArray()));
                found = read(ps);
            }
            List<Schedule> paused = new ArrayList<>();
            for (Schedule s : found) {
                try (PreparedStatement ps = c.prepareStatement("UPDATE kilnmark.schedule SET enabled = false, "
                    + "next_at = NULL, last_at = clock_timestamp(), last_outcome = ?, last_pipeline_run = NULL, "
                    + "last_flow_run = NULL WHERE id = ?")) {
                    ps.setString(1, PAUSED);
                    ps.setLong(2, s.id());
                    ps.executeUpdate();
                }
                paused.add(s);
            }
            return paused;
        });
    }

    static String clip(String outcome) {
        return outcome.length() <= MAX_OUTCOME ? outcome : outcome.substring(0, MAX_OUTCOME - 3) + "...";
    }

    /**
     * Writes a fire's outcome again, in a transaction of its own, after writing it with the fire
     * failed (the run may already be queued): only while the row still says {@link #STARTING}, so a
     * run started is never left reading as a lost fire. Returns whether it was written.
     */
    public boolean recordAfterFailure(long id, Fired f) {
        return db.tx(c -> {
            try (PreparedStatement ps = c.prepareStatement("UPDATE kilnmark.schedule SET last_outcome = ?, "
                + "last_pipeline_run = ?, last_flow_run = ? WHERE id = ? AND last_outcome = ?")) {
                ps.setString(1, clip(f.outcome()));
                ps.setObject(2, f.pipelineRun(), Types.BIGINT);
                ps.setObject(3, f.flowRun(), Types.BIGINT);
                ps.setLong(4, id);
                ps.setString(5, STARTING);
                return ps.executeUpdate() == 1;
            }
        });
    }

    /** The later of two times; {@code b} may be null. */
    static Instant later(Instant a, Instant b) {
        return b != null && b.isAfter(a) ? b : a;
    }

    /** The database's clock, now: the same for every process, whatever their own clocks say. */
    private static Instant clock(Connection c) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT clock_timestamp()");
             ResultSet rs = ps.executeQuery()) {
            rs.next();
            return Db.instant(rs.getTimestamp(1));
        }
    }

    /** Sets what a person chooses, from parameter {@code from} on; returns the next parameter's index. */
    private static int set(PreparedStatement ps, Setting s, int from) throws SQLException {
        int i = from;
        ps.setString(i++, s.pipeline());
        ps.setString(i++, s.flow());
        ps.setString(i++, s.cron());
        ps.setString(i++, s.zone());
        ps.setString(i++, s.parameters());
        ps.setString(i++, s.runAs());
        ps.setBoolean(i++, s.enabled());
        ps.setString(i++, s.description());
        return i;
    }

    private Optional<Schedule> find(Connection c, long id, boolean lock) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT " + columns + " FROM kilnmark.schedule WHERE id = ?"
            + (lock ? " FOR UPDATE" : ""))) {
            ps.setLong(1, id);
            return read(ps).stream().findFirst();
        }
    }

    /** Rows as schedules; a fire left {@link #STARTING} for too long reads as {@link #UNQUEUED}. */
    private static List<Schedule> read(PreparedStatement ps) throws SQLException {
        List<Schedule> out = new ArrayList<>();
        try (ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                String outcome = rs.getBoolean(19) ? UNQUEUED : rs.getString(16);
                out.add(new Schedule(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5),
                    rs.getString(6), rs.getString(7), rs.getBoolean(8), rs.getString(9), rs.getString(10),
                    Db.instant(rs.getTimestamp(11)), rs.getString(12), Db.instant(rs.getTimestamp(13)),
                    Db.instant(rs.getTimestamp(14)), Db.instant(rs.getTimestamp(15)), outcome,
                    rs.getObject(17, Long.class), rs.getObject(18, Long.class), rs.getObject(20, Long.class),
                    rs.getString(21)));
            }
        }
        return out;
    }
}

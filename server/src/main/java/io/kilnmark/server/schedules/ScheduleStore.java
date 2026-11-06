package io.kilnmark.server.schedules;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Supplier;

import io.kilnmark.server.Db;
import jakarta.enterprise.context.ApplicationScoped;

/** The {@code schedule} table (V36): saving schedules, and claiming those that are due. */
@ApplicationScoped
public class ScheduleStore {

    private static final String COLUMNS = "id, pipeline, flow, cron, zone, parameters::text, run_as, enabled, "
        + "description, created_by, created_at, updated_by, updated_at, next_at, last_at, last_outcome, "
        + "last_pipeline_run, last_flow_run";

    /** The longest outcome kept: a server message can be long. */
    static final int MAX_OUTCOME = 500;

    private final Db db;

    public ScheduleStore(Db db) {
        this.db = db;
    }

    /** A schedule as stored; {@code parameters} is its JSON object as text. */
    public record Schedule(long id, String pipeline, String flow, String cron, String zone, String parameters,
                           String runAs, boolean enabled, String description, String createdBy, Instant createdAt,
                           String updatedBy, Instant updatedAt, Instant nextAt, Instant lastAt, String lastOutcome,
                           Long lastPipelineRun, Long lastFlowRun) {
    }

    /** What a schedule is set to: everything a person chooses, and when it fires next. */
    public record Setting(String pipeline, String flow, String cron, String zone, String parameters, String runAs,
                          boolean enabled, String description, Instant nextAt) {
    }

    /**
     * What claiming a due schedule does: when it fires next (null: never again), and an outcome to
     * record now when it is not to run (null: run it). A fire that is to run records
     * {@link #UNQUEUED} until its own outcome replaces it, so a crash between the claim and the queue
     * is seen, not hidden behind the last fire's outcome.
     */
    public record Claim(Instant nextAt, String outcome) {
    }

    /** The outcome a claimed fire has until its run is queued; it stays only if the server stopped first. */
    public static final String UNQUEUED = "failed: the server stopped before the run was queued";

    /** A schedule claimed to run: as it was when claimed, with when the fire was due. */
    public record Due(Schedule schedule, Instant dueAt) {
    }

    /** Every schedule, or those of one pipeline or flow, by id. */
    public List<Schedule> list(String pipeline, String flow) {
        return db.tx(c -> {
            StringBuilder sql = new StringBuilder("SELECT " + COLUMNS + " FROM kilnmark.schedule WHERE true");
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

    /** Adds a schedule set by {@code by}. */
    public Schedule insert(Setting s, String by) {
        return db.tx(c -> {
            long id;
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO kilnmark.schedule (pipeline, flow, cron, zone, "
                + "parameters, run_as, enabled, description, created_by, updated_by, next_at) "
                + "VALUES (?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?, ?, ?) RETURNING id")) {
                int i = set(ps, s, 1);
                ps.setString(i++, by);
                ps.setString(i++, by);
                ps.setTimestamp(i, Db.timestamp(s.nextAt()));
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
     * first and {@code nextAt} asked for only then, so a fire a scheduler claimed meanwhile is never
     * put back (it would fire twice). Its last fire stays, unless it now runs something else.
     */
    public Optional<Schedule> update(long id, Setting s, Supplier<Instant> nextAt, String by) {
        return db.tx(c -> {
            if (find(c, id, true).isEmpty()) {
                return Optional.empty();
            }
            try (PreparedStatement ps = c.prepareStatement("UPDATE kilnmark.schedule SET "
                + "last_outcome = CASE WHEN pipeline IS NOT DISTINCT FROM ? AND flow IS NOT DISTINCT FROM ? "
                + "THEN last_outcome END, "
                + "last_pipeline_run = CASE WHEN pipeline IS NOT DISTINCT FROM ? THEN last_pipeline_run END, "
                + "last_flow_run = CASE WHEN flow IS NOT DISTINCT FROM ? THEN last_flow_run END, "
                + "pipeline = ?, flow = ?, cron = ?, zone = ?, parameters = ?::jsonb, run_as = ?, enabled = ?, "
                + "description = ?, updated_by = ?, updated_at = now(), next_at = ? WHERE id = ?")) {
                ps.setString(1, s.pipeline());
                ps.setString(2, s.flow());
                ps.setString(3, s.pipeline());
                ps.setString(4, s.flow());
                int i = set(ps, s, 5);
                ps.setString(i++, by);
                ps.setTimestamp(i++, Db.timestamp(nextAt.get()));
                ps.setLong(i, id);
                ps.executeUpdate();
            }
            return find(c, id, false);
        });
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
     * Claims up to {@code limit} enabled schedules due at {@code now}, in one short transaction:
     * each is locked (those another scheduler holds are skipped), {@code plan} says when it fires
     * next and whether it runs, and that is written with {@code last_at = now} before the
     * transaction commits. Returns those to run. A schedule is claimed once, however many
     * schedulers look at the same time: the claim moves {@code next_at} past {@code now}.
     */
    public List<Due> claimDue(Instant now, int limit, Function<Schedule, Claim> plan) {
        return db.tx(c -> {
            List<Schedule> due;
            try (PreparedStatement ps = c.prepareStatement("SELECT " + COLUMNS + " FROM kilnmark.schedule "
                + "WHERE enabled AND next_at <= ? ORDER BY next_at, id LIMIT ? FOR UPDATE SKIP LOCKED")) {
                ps.setTimestamp(1, Db.timestamp(now));
                ps.setInt(2, limit);
                due = read(ps);
            }
            List<Due> run = new ArrayList<>();
            for (Schedule s : due) {
                Claim claim = plan.apply(s);
                try (PreparedStatement ps = c.prepareStatement("UPDATE kilnmark.schedule SET next_at = ?, last_at = ?, "
                    + "last_outcome = ?, last_pipeline_run = NULL, last_flow_run = NULL WHERE id = ?")) {
                    ps.setTimestamp(1, Db.timestamp(claim.nextAt()));
                    ps.setTimestamp(2, Db.timestamp(now));
                    ps.setString(3, clip(claim.outcome() == null ? UNQUEUED : claim.outcome()));
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

    /** Records what a fire did, with the run it started, if any. A schedule deleted since is left alone. */
    public void recordOutcome(long id, String outcome, Long pipelineRun, Long flowRun) {
        db.tx(c -> {
            try (PreparedStatement ps = c.prepareStatement("UPDATE kilnmark.schedule SET last_outcome = ?, "
                + "last_pipeline_run = ?, last_flow_run = ? WHERE id = ?")) {
                ps.setString(1, clip(outcome));
                ps.setObject(2, pipelineRun, Types.BIGINT);
                ps.setObject(3, flowRun, Types.BIGINT);
                ps.setLong(4, id);
                return ps.executeUpdate();
            }
        });
    }

    static String clip(String outcome) {
        return outcome.length() <= MAX_OUTCOME ? outcome : outcome.substring(0, MAX_OUTCOME - 3) + "...";
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

    private static Optional<Schedule> find(Connection c, long id, boolean lock) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT " + COLUMNS + " FROM kilnmark.schedule WHERE id = ?"
            + (lock ? " FOR UPDATE" : ""))) {
            ps.setLong(1, id);
            return read(ps).stream().findFirst();
        }
    }

    private static List<Schedule> read(PreparedStatement ps) throws SQLException {
        List<Schedule> out = new ArrayList<>();
        try (ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                out.add(new Schedule(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5),
                    rs.getString(6), rs.getString(7), rs.getBoolean(8), rs.getString(9), rs.getString(10),
                    Db.instant(rs.getTimestamp(11)), rs.getString(12), Db.instant(rs.getTimestamp(13)),
                    Db.instant(rs.getTimestamp(14)), Db.instant(rs.getTimestamp(15)), rs.getString(16),
                    rs.getObject(17, Long.class), rs.getObject(18, Long.class)));
            }
        }
        return out;
    }
}

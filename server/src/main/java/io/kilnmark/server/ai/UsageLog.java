package io.kilnmark.server.ai;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Types;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import org.jboss.logging.Logger;

import io.kilnmark.graph.store.Records;
import io.kilnmark.server.Db;
import io.kilnmark.server.KilnmarkConfig;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * Usage records: one row per model call, refused ones included. Prompts and answers are kept only
 * when {@code kilnmark.ai.keep-usage-text} is on. A failure to record never fails the call.
 */
@ApplicationScoped
public class UsageLog {

    private static final Logger LOG = Logger.getLogger(UsageLog.class);

    private final Db db;
    private final KilnmarkConfig config;

    public UsageLog(Db db, KilnmarkConfig config) {
        this.db = db;
        this.config = config;
    }

    /** One call. {@code profile} and {@code source} may be null; {@code outcome} as in the table's check. */
    public record Entry(String feature, ModelProfile profile, Records.Source source, Integer tokensIn,
                        Integer tokensOut, long latencyMs, String outcome, String detail, String caller, String prompt,
                        String answer) {
    }

    public void record(Entry e) {
        boolean keep = config.ai().keepUsageText();
        try {
            db.tx(c -> {
                try (PreparedStatement ps = c.prepareStatement("""
                    INSERT INTO kilnmark.ai_usage (feature, profile_id, profile_name, provider, model, local, source_id,
                        source_name, tokens_in, tokens_out, latency_ms, outcome, detail, caller, prompt_text, answer_text)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""")) {
                    ps.setString(1, e.feature());
                    ModelProfile p = e.profile();
                    if (p == null) {
                        ps.setNull(2, Types.BIGINT);
                        ps.setNull(3, Types.VARCHAR);
                        ps.setNull(4, Types.VARCHAR);
                        ps.setNull(5, Types.VARCHAR);
                        ps.setNull(6, Types.BOOLEAN);
                    } else {
                        ps.setLong(2, p.id());
                        ps.setString(3, p.name());
                        ps.setString(4, p.provider().id());
                        ps.setString(5, p.model());
                        ps.setBoolean(6, p.local());
                    }
                    if (e.source() == null) {
                        ps.setNull(7, Types.BIGINT);
                        ps.setNull(8, Types.VARCHAR);
                    } else {
                        ps.setLong(7, e.source().id());
                        ps.setString(8, e.source().name());
                    }
                    setInt(ps, 9, e.tokensIn());
                    setInt(ps, 10, e.tokensOut());
                    ps.setInt(11, (int) Math.min(Integer.MAX_VALUE, Math.max(0, e.latencyMs())));
                    ps.setString(12, e.outcome());
                    ps.setString(13, e.detail() == null ? null : truncate(e.detail(), 1000));
                    ps.setString(14, truncate(e.caller(), 200));
                    ps.setString(15, keep && e.prompt() != null ? truncate(e.prompt(), 100_000) : null);
                    ps.setString(16, keep && e.answer() != null ? truncate(e.answer(), 100_000) : null);
                    ps.executeUpdate();
                }
                return null;
            });
        } catch (RuntimeException ex) {
            LOG.warnf("Could not record an AI call for %s: %s", e.feature(), ex.getMessage());
        }
    }

    private static void setInt(PreparedStatement ps, int i, Integer v) throws java.sql.SQLException {
        if (v == null) {
            ps.setNull(i, Types.INTEGER);
        } else {
            ps.setInt(i, v);
        }
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max);
    }

    /** Totals for one day and one profile (or none, for calls refused before a profile was known). */
    public record Total(LocalDate day, String profile, long calls, long ok, long refused, long failed, long tokensIn,
                        long tokensOut, long averageLatencyMs) {
    }

    /** A recent call, without prompt or answer text. */
    public record Recent(long id, Instant at, String feature, String profile, String model, String source,
                         Integer tokensIn, Integer tokensOut, int latencyMs, String outcome, String detail,
                         String caller) {
    }

    /** Totals by day and profile for the last {@code days} days, newest day first. */
    public List<Total> totals(int days) {
        return db.tx(c -> {
            List<Total> out = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement("""
                SELECT (at AT TIME ZONE 'UTC')::date AS day, profile_name, count(*),
                       count(*) FILTER (WHERE outcome = 'ok'), count(*) FILTER (WHERE outcome = 'refused'),
                       count(*) FILTER (WHERE outcome NOT IN ('ok', 'refused')),
                       coalesce(sum(tokens_in), 0), coalesce(sum(tokens_out), 0),
                       coalesce(round(avg(latency_ms) FILTER (WHERE outcome = 'ok')), 0)
                FROM kilnmark.ai_usage
                WHERE at >= now() - make_interval(days => ?)
                GROUP BY 1, 2 ORDER BY 1 DESC, 2 NULLS LAST""")) {
                ps.setInt(1, days);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        out.add(new Total(rs.getObject(1, LocalDate.class), rs.getString(2), rs.getLong(3),
                            rs.getLong(4), rs.getLong(5), rs.getLong(6), rs.getLong(7), rs.getLong(8), rs.getLong(9)));
                    }
                }
            }
            return out;
        });
    }

    public List<Recent> recent(int limit) {
        return db.tx(c -> {
            List<Recent> out = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement("""
                SELECT id, at, feature, profile_name, model, source_name, tokens_in, tokens_out, latency_ms, outcome,
                       detail, caller
                FROM kilnmark.ai_usage ORDER BY id DESC LIMIT ?""")) {
                ps.setInt(1, limit);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        int in = rs.getInt(7);
                        Integer tokensIn = rs.wasNull() ? null : in;
                        int outTokens = rs.getInt(8);
                        Integer tokensOut = rs.wasNull() ? null : outTokens;
                        out.add(new Recent(rs.getLong(1), Db.instant(rs.getTimestamp(2)), rs.getString(3),
                            rs.getString(4), rs.getString(5), rs.getString(6), tokensIn, tokensOut, rs.getInt(9),
                            rs.getString(10), rs.getString(11), rs.getString(12)));
                    }
                }
            }
            return out;
        });
    }
}

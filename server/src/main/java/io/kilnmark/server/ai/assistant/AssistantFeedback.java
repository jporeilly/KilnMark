package io.kilnmark.server.ai.assistant;

import java.sql.Array;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Types;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import io.kilnmark.server.Db;
import io.kilnmark.server.KilnmarkConfig;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * Thumbs up or down on the assistant's answers ({@code assistant_feedback}, V11), with the
 * question and the sections used, so admins see where the documentation falls short. The answer's
 * text is kept only while {@code kilnmark.ai.keep-usage-text} is on, as for usage records.
 */
@ApplicationScoped
public class AssistantFeedback {

    static final int MAX_COMMENT = 2000;
    static final int MAX_SECTIONS = 20;

    private final Db db;
    private final KilnmarkConfig config;

    public AssistantFeedback(Db db, KilnmarkConfig config) {
        this.db = db;
        this.config = config;
    }

    /** Feedback as sent: {@code answerId} from the answer, {@code answer} kept only when usage text is kept. */
    public record Input(String question, String answerId, String answer, List<String> sectionIds, Boolean helpful,
                        String comment, String page, Boolean fallback) {
    }

    /** Stored feedback. {@code answer} is null unless usage text was kept when it was given. */
    public record Entry(long id, Instant at, String caller, String question, String answerId, String answer,
                        List<String> sectionIds, String page, boolean helpful, String comment, boolean fallback) {
    }

    public long record(Input in, String caller) {
        if (in == null || in.question() == null || in.question().isBlank()) {
            throw new IllegalArgumentException("question: the question the answer was for");
        }
        if (in.question().length() > Assistant.MAX_QUESTION) {
            throw new IllegalArgumentException("question: 2,000 characters at most");
        }
        if (in.helpful() == null) {
            throw new IllegalArgumentException("helpful: true for a thumbs up, false for a thumbs down");
        }
        if (in.answerId() != null && !in.answerId().matches("[0-9a-f]{8,64}")) {
            throw new IllegalArgumentException("answerId: the answerId the answer came with");
        }
        if (in.comment() != null && in.comment().length() > MAX_COMMENT) {
            throw new IllegalArgumentException("comment: 2,000 characters at most");
        }
        List<String> ids = in.sectionIds() == null ? List.of() : in.sectionIds();
        if (ids.size() > MAX_SECTIONS
            || ids.stream().anyMatch(s -> s == null || !s.matches("[a-z0-9_-]{1,64}(/[^\\s/]{1,200})?"))) {
            throw new IllegalArgumentException("sectionIds: the sectionId of each source of the answer, " + MAX_SECTIONS
                + " at most");
        }
        String page = Assistant.validPage(in.page());
        boolean keep = config.ai().keepUsageText();
        return db.tx(c -> {
            try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO kilnmark.assistant_feedback (caller, question, answer_id, answer_text, section_ids, page,
                    helpful, comment, fallback)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?) RETURNING id""")) {
                ps.setString(1, caller.length() > 200 ? caller.substring(0, 200) : caller);
                ps.setString(2, in.question().strip());
                ps.setString(3, in.answerId());
                if (keep && in.answer() != null) {
                    ps.setString(4, in.answer().length() > 20_000 ? in.answer().substring(0, 20_000) : in.answer());
                } else {
                    ps.setNull(4, Types.VARCHAR);
                }
                ps.setArray(5, c.createArrayOf("text", ids.toArray()));
                ps.setString(6, page);
                ps.setBoolean(7, in.helpful());
                String comment = in.comment() == null || in.comment().isBlank() ? null : in.comment().strip();
                ps.setString(8, comment);
                ps.setBoolean(9, Boolean.TRUE.equals(in.fallback()));
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    return rs.getLong(1);
                }
            }
        });
    }

    /** The latest feedback, newest first; {@code helpful} null for both kinds. */
    public List<Entry> recent(int limit, Boolean helpful) {
        return db.tx(c -> {
            List<Entry> out = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement("""
                SELECT id, at, caller, question, answer_id, answer_text, section_ids, page, helpful, comment, fallback
                FROM kilnmark.assistant_feedback WHERE (?::boolean IS NULL OR helpful = ?::boolean)
                ORDER BY id DESC LIMIT ?""")) {
                if (helpful == null) {
                    ps.setNull(1, Types.BOOLEAN);
                    ps.setNull(2, Types.BOOLEAN);
                } else {
                    ps.setBoolean(1, helpful);
                    ps.setBoolean(2, helpful);
                }
                ps.setInt(3, limit);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        Array ids = rs.getArray(7);
                        out.add(new Entry(rs.getLong(1), Db.instant(rs.getTimestamp(2)), rs.getString(3),
                            rs.getString(4), rs.getString(5), rs.getString(6),
                            ids == null ? List.of() : List.of((String[]) ids.getArray()), rs.getString(8),
                            rs.getBoolean(9), rs.getString(10), rs.getBoolean(11)));
                    }
                }
            }
            return out;
        });
    }
}

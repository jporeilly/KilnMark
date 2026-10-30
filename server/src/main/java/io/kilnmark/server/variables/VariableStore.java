package io.kilnmark.server.variables;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import io.kilnmark.server.Db;
import jakarta.enterprise.context.ApplicationScoped;

/** Server variables (V22): one row each, and every change kept in {@code variable_change}. */
@ApplicationScoped
public class VariableStore {

    /** A variable: {@code value} is JSON text (a string, a number, true or false). */
    public record Variable(String name, String type, String value, String description, String updatedBy,
                           Instant updatedAt) {
    }

    /** One change: the JSON text before and after (null when created or deleted). */
    public record Change(String name, String oldValue, String newValue, String changedBy, Instant changedAt) {
    }

    private final Db db;

    public VariableStore(Db db) {
        this.db = db;
    }

    public List<Variable> list() {
        return db.tx(c -> {
            List<Variable> out = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement("SELECT name, type, value::text, description, updated_by, "
                + "updated_at FROM kilnmark.variable ORDER BY name"); ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(read(rs));
                }
            }
            return out;
        });
    }

    public Optional<Variable> find(String name) {
        return db.tx(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT name, type, value::text, description, updated_by, "
                + "updated_at FROM kilnmark.variable WHERE name = ?")) {
                ps.setString(1, name);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? Optional.of(read(rs)) : Optional.empty();
                }
            }
        });
    }

    /**
     * Creates or replaces a variable and records the change, in one transaction.
     *
     * @param onlyIfMissing leave a variable that exists as it is (seeding from the environment)
     * @return whether anything was written
     */
    public boolean put(String name, String type, String valueJson, String description, String by,
                       boolean onlyIfMissing) {
        return db.tx(c -> {
            // One writer per name at a time, also while the row does not exist yet (FOR UPDATE locks nothing then).
            try (PreparedStatement ps = c.prepareStatement("SELECT pg_advisory_xact_lock(hashtext('variable:' || ?))")) {
                ps.setString(1, name);
                ps.executeQuery().close();
            }
            String before = null;
            try (PreparedStatement ps = c.prepareStatement("SELECT value::text FROM kilnmark.variable WHERE name = ? "
                + "FOR UPDATE")) {
                ps.setString(1, name);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        if (onlyIfMissing) {
                            return false;
                        }
                        before = rs.getString(1);
                    }
                }
            }
            try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO kilnmark.variable (name, type, value, description, updated_by) VALUES (?, ?, ?::jsonb, ?, ?)
                ON CONFLICT (name) DO UPDATE SET type = EXCLUDED.type, value = EXCLUDED.value,
                    description = EXCLUDED.description, updated_by = EXCLUDED.updated_by, updated_at = now()""")) {
                ps.setString(1, name);
                ps.setString(2, type);
                ps.setString(3, valueJson);
                ps.setString(4, description);
                ps.setString(5, by);
                ps.executeUpdate();
            }
            change(c, name, before, valueJson, by);
            return true;
        });
    }

    /** Deletes a variable and records it; false when there was none. */
    public boolean delete(String name, String by) {
        return db.tx(c -> {
            String before;
            try (PreparedStatement ps = c.prepareStatement("DELETE FROM kilnmark.variable WHERE name = ? "
                + "RETURNING value::text")) {
                ps.setString(1, name);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        return false;
                    }
                    before = rs.getString(1);
                }
            }
            change(c, name, before, null, by);
            return true;
        });
    }

    /** The newest changes of one variable first, at most {@code limit}. */
    public List<Change> changes(String name, int limit) {
        return db.tx(c -> {
            List<Change> out = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement("SELECT name, old_value::text, new_value::text, changed_by, "
                + "changed_at FROM kilnmark.variable_change WHERE name = ? ORDER BY changed_at DESC, id DESC LIMIT ?")) {
                ps.setString(1, name);
                ps.setInt(2, limit);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        out.add(new Change(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                            Db.instant(rs.getTimestamp(5))));
                    }
                }
            }
            return out;
        });
    }

    private static void change(java.sql.Connection c, String name, String before, String after, String by)
        throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("INSERT INTO kilnmark.variable_change (name, old_value, "
            + "new_value, changed_by) VALUES (?, ?::jsonb, ?::jsonb, ?)")) {
            ps.setString(1, name);
            ps.setString(2, before);
            ps.setString(3, after);
            ps.setString(4, by);
            ps.executeUpdate();
        }
    }

    private static Variable read(ResultSet rs) throws SQLException {
        return new Variable(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5),
            Db.instant(rs.getTimestamp(6)));
    }
}

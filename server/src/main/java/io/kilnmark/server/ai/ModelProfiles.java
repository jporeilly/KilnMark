package io.kilnmark.server.ai;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import io.kilnmark.server.Db;
import io.kilnmark.server.SecretBox;
import io.kilnmark.server.ai.AiFeature.Purpose;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * Model profiles, feature routes and server-wide AI settings, in the server's database (V6).
 * API keys are sealed with {@link SecretBox} on the way in and opened only by {@link #apiKey}.
 */
@ApplicationScoped
public class ModelProfiles {

    /** Setting key: {@code allowed} or {@code forbidden}, for models not marked local. */
    public static final String HOSTED_MODELS = "hosted_models";

    private final Db db;
    private final SecretBox secrets;

    public ModelProfiles(Db db, SecretBox secrets) {
        this.db = db;
        this.secrets = secrets;
    }

    private static final String COLUMNS = "p.id, p.name, p.provider, p.base_url, p.model, p.purpose, p.dimensions, "
        + "p.context_size, p.local, p.api_key IS NOT NULL, p.api_key_hint, p.timeout_seconds, p.max_concurrent, "
        + "p.version, p.created_by, p.created_at, p.updated_at";

    private static ModelProfile row(ResultSet rs) throws SQLException {
        int dims = rs.getInt(7);
        Integer dimensions = rs.wasNull() ? null : dims;
        int ctx = rs.getInt(8);
        Integer context = rs.wasNull() ? null : ctx;
        return new ModelProfile(rs.getLong(1), rs.getString(2), Provider.parse(rs.getString(3)), rs.getString(4),
            rs.getString(5), Purpose.parse(rs.getString(6)), dimensions, context, rs.getBoolean(9), rs.getBoolean(10),
            rs.getString(11), rs.getInt(12), rs.getInt(13), rs.getInt(14), rs.getString(15),
            Db.instant(rs.getTimestamp(16)), Db.instant(rs.getTimestamp(17)));
    }

    public List<ModelProfile> list() {
        return db.tx(c -> {
            List<ModelProfile> out = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement("SELECT " + COLUMNS + " FROM kilnmark.model_profile p "
                + "ORDER BY p.name"); ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(row(rs));
                }
            }
            return out;
        });
    }

    public Optional<ModelProfile> find(String name) {
        return one("p.name = ?", ps -> ps.setString(1, name));
    }

    public Optional<ModelProfile> get(long id) {
        return one("p.id = ?", ps -> ps.setLong(1, id));
    }

    private interface Binder {
        void bind(PreparedStatement ps) throws SQLException;
    }

    private Optional<ModelProfile> one(String where, Binder binder) {
        return db.tx(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT " + COLUMNS + " FROM kilnmark.model_profile p "
                + "WHERE " + where)) {
                binder.bind(ps);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? Optional.of(row(rs)) : Optional.empty();
                }
            }
        });
    }

    /** The profile's API key, decrypted, for the model client only. Never log or return it. */
    Optional<String> apiKey(long id) {
        return db.tx(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT api_key FROM kilnmark.model_profile WHERE id = ?")) {
                ps.setLong(1, id);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() && rs.getBytes(1) != null ? Optional.of(secrets.open(rs.getBytes(1)))
                        : Optional.empty();
                }
            }
        });
    }

    public ModelProfile create(ModelProfile.Valid v, String caller) {
        if (find(v.name()).isPresent()) {
            throw new Conflict("A model profile called " + v.name() + " already exists");
        }
        long id = db.tx(c -> {
            try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO kilnmark.model_profile (name, provider, base_url, model, purpose, dimensions, context_size,
                    local, api_key, api_key_hint, timeout_seconds, max_concurrent, created_by)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) RETURNING id""")) {
                bindCommon(ps, v);
                String key = v.apiKey() == null || v.apiKey().isEmpty() ? null : v.apiKey();
                ps.setBytes(9, key == null ? null : secrets.seal(key));
                ps.setString(10, ModelProfile.hint(key));
                ps.setInt(11, v.timeoutSeconds());
                ps.setInt(12, v.maxConcurrent());
                ps.setString(13, caller);
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    return rs.getLong(1);
                }
            }
        });
        return get(id).orElseThrow();
    }

    private static void bindCommon(PreparedStatement ps, ModelProfile.Valid v) throws SQLException {
        ps.setString(1, v.name());
        ps.setString(2, v.provider().id());
        ps.setString(3, v.baseUrl());
        ps.setString(4, v.model());
        ps.setString(5, v.purpose().id());
        setInt(ps, 6, v.dimensions());
        setInt(ps, 7, v.contextSize());
        ps.setBoolean(8, v.local());
    }

    private static void setInt(PreparedStatement ps, int i, Integer value) throws SQLException {
        if (value == null) {
            ps.setNull(i, Types.INTEGER);
        } else {
            ps.setInt(i, value);
        }
    }

    /** What an update changed: {@code reembed} is true when existing embeddings no longer fit. */
    public record Updated(ModelProfile profile, boolean reembed) {
    }

    /** Changes a profile. A null key keeps the stored one; an empty key removes it. */
    public Updated update(ModelProfile before, ModelProfile.Valid v, String caller) {
        if (!before.name().equals(v.name()) && find(v.name()).isPresent()) {
            throw new Conflict("A model profile called " + v.name() + " already exists");
        }
        if (before.purpose() != v.purpose() && routed(before.id())) {
            throw new Conflict("Profile " + before.name() + " is in use by " + String.join(", ", featuresUsing(before.id()))
                + ", so its purpose cannot change. Move those features to another profile first.");
        }
        db.tx(c -> {
            try (PreparedStatement ps = c.prepareStatement("""
                UPDATE kilnmark.model_profile SET name = ?, provider = ?, base_url = ?, model = ?, purpose = ?,
                    dimensions = ?, context_size = ?, local = ?, timeout_seconds = ?, max_concurrent = ?,
                    version = version + 1, updated_at = now()
                WHERE id = ?""")) {
                bindCommon(ps, v);
                ps.setInt(9, v.timeoutSeconds());
                ps.setInt(10, v.maxConcurrent());
                ps.setLong(11, before.id());
                ps.executeUpdate();
            }
            if (v.apiKey() != null) {
                String key = v.apiKey().isEmpty() ? null : v.apiKey();
                try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE kilnmark.model_profile SET api_key = ?, api_key_hint = ? WHERE id = ?")) {
                    ps.setBytes(1, key == null ? null : secrets.seal(key));
                    ps.setString(2, ModelProfile.hint(key));
                    ps.setLong(3, before.id());
                    ps.executeUpdate();
                }
            }
            return null;
        });
        ModelProfile after = get(before.id()).orElseThrow();
        boolean reembed = after.purpose() == Purpose.EMBEDDING && (before.provider() != after.provider()
            || !Objects.equals(before.effectiveBaseUrl(), after.effectiveBaseUrl())
            || !before.model().equals(after.model()) || !Objects.equals(before.dimensions(), after.dimensions()));
        return new Updated(after, reembed);
    }

    /** Deletes a profile that no feature uses. Its embeddings go with it; usage records keep its name. */
    public void delete(ModelProfile p) {
        if (routed(p.id())) {
            throw new Conflict("Profile " + p.name() + " is in use by " + String.join(", ", featuresUsing(p.id()))
                + ". Move those features to another profile, or turn them off, first.");
        }
        db.tx(c -> {
            try (PreparedStatement ps = c.prepareStatement("DELETE FROM kilnmark.model_profile WHERE id = ?")) {
                ps.setLong(1, p.id());
                ps.executeUpdate();
            }
            return null;
        });
    }

    private boolean routed(long profileId) {
        return !featuresUsing(profileId).isEmpty();
    }

    private List<String> featuresUsing(long profileId) {
        return db.tx(c -> {
            List<String> out = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement(
                "SELECT feature FROM kilnmark.ai_route WHERE profile_id = ? ORDER BY feature")) {
                ps.setLong(1, profileId);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        out.add(rs.getString(1));
                    }
                }
            }
            return out;
        });
    }

    /** Every feature that has a profile. Rows naming a feature this version does not know are ignored. */
    public Map<AiFeature, ModelProfile> routes() {
        Map<AiFeature, Long> ids = db.tx(c -> {
            Map<AiFeature, Long> out = new EnumMap<>(AiFeature.class);
            try (PreparedStatement ps = c.prepareStatement("SELECT feature, profile_id FROM kilnmark.ai_route");
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    try {
                        out.put(AiFeature.parse(rs.getString(1)), rs.getLong(2));
                    } catch (IllegalArgumentException e) {
                        // a feature from a newer version
                    }
                }
            }
            return out;
        });
        Map<AiFeature, ModelProfile> out = new EnumMap<>(AiFeature.class);
        ids.forEach((f, id) -> get(id).ifPresent(p -> out.put(f, p)));
        return out;
    }

    /** The profile a feature uses, if it has one. */
    public Optional<ModelProfile> route(AiFeature feature) {
        return one("p.id = (SELECT profile_id FROM kilnmark.ai_route WHERE feature = ?)",
            ps -> ps.setString(1, feature.id()));
    }

    /** Sends a feature to a profile, or turns it off when {@code profile} is null. */
    public void setRoute(AiFeature feature, ModelProfile profile, String caller) {
        if (profile != null && profile.purpose() != feature.purpose()) {
            throw new IllegalArgumentException("profile: " + feature.label() + " needs a " + feature.purpose().id()
                + " model, and " + profile.name() + " is a " + profile.purpose().id() + " model");
        }
        db.tx(c -> {
            if (profile == null) {
                try (PreparedStatement ps = c.prepareStatement("DELETE FROM kilnmark.ai_route WHERE feature = ?")) {
                    ps.setString(1, feature.id());
                    ps.executeUpdate();
                }
            } else {
                try (PreparedStatement ps = c.prepareStatement("""
                    INSERT INTO kilnmark.ai_route (feature, profile_id, updated_by) VALUES (?, ?, ?)
                    ON CONFLICT (feature) DO UPDATE SET profile_id = EXCLUDED.profile_id,
                        updated_by = EXCLUDED.updated_by, updated_at = now()""")) {
                    ps.setString(1, feature.id());
                    ps.setLong(2, profile.id());
                    ps.setString(3, caller);
                    ps.executeUpdate();
                }
            }
            return null;
        });
    }

    public Optional<String> setting(String key) {
        return db.tx(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT value FROM kilnmark.ai_setting WHERE key = ?")) {
                ps.setString(1, key);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? Optional.of(rs.getString(1)) : Optional.empty();
                }
            }
        });
    }

    public void putSetting(String key, String value, String caller) {
        db.tx(c -> {
            try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO kilnmark.ai_setting (key, value, updated_by) VALUES (?, ?, ?)
                ON CONFLICT (key) DO UPDATE SET value = EXCLUDED.value, updated_by = EXCLUDED.updated_by,
                    updated_at = now()""")) {
                ps.setString(1, key);
                ps.setString(2, value);
                ps.setString(3, caller);
                ps.executeUpdate();
            }
            return null;
        });
    }

    /** A change that clashes with what is stored; the API answers 409 with the message. */
    public static final class Conflict extends RuntimeException {
        @java.io.Serial
        private static final long serialVersionUID = 1L;

        public Conflict(String message) {
            super(message);
        }
    }
}

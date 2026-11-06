package io.kilnmark.server.api;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.LongSupplier;

import org.jboss.logging.Logger;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import io.kilnmark.server.Db;
import io.kilnmark.server.KilnmarkConfig;
import io.kilnmark.server.Role;
import io.kilnmark.server.auth.ApiTokenIdentityProvider;
import io.kilnmark.server.auth.OidcIdentityAugmentor;
import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

/**
 * Request keys for starting runs (ADR 0047): the same {@code Idempotency-Key} from the same caller,
 * for the same request (the same pipeline or flow, version and parameters) within 24 hours, gives
 * the run the first request started instead of starting another; the same key for a different
 * request is refused (422). Stored in {@code kilnmark.run_request_key} (V37). Keys older than 24
 * hours count as unused, and are deleted shortly after start and then every hour on processes with
 * the scheduler role.
 *
 * <p>Race-safe and all-or-nothing: the run is checked first, outside any transaction; then the key's
 * row is written and the run queued in one transaction ({@link Db#together}), so the key and its
 * run commit together or not at all. A second request with the same key waits on the first's
 * uncommitted row, then finds its run; if the first could not start a run, its row is rolled back
 * and the second tries itself.
 */
@ApplicationScoped
public class RunRequestKeys {

    private static final Logger LOG = Logger.getLogger(RunRequestKeys.class);
    /** How long a key is kept. */
    public static final Duration KEPT = Duration.ofHours(24);
    /** The longest key. */
    public static final int MAX_LENGTH = 200;
    private static final Duration EVERY = Duration.ofHours(1);
    /** Writes a request the same way every time: map keys sorted. */
    private static final ObjectMapper CANONICAL = new ObjectMapper()
        .configure(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS, true);

    private final Db db;
    private final KilnmarkConfig config;
    private volatile Thread thread;
    /** Tests only: run after the run is queued and before the key commits. */
    private volatile Runnable afterQueue = () -> { };
    /** Tests only: run after the key's row is written and before the run is queued. */
    private volatile Runnable afterKey = () -> { };

    public RunRequestKeys(Db db, KilnmarkConfig config) {
        this.db = db;
        this.config = config;
    }

    /** The run a request started, and whether an earlier request with the same key had started it. */
    public record Started(long runId, boolean repeated) {
    }

    /**
     * Whose key space a request is in: its token, or the person signed in with the identity provider
     * as the provider fixes them (issuer and subject, which no name change alters).
     */
    public static String caller(SecurityIdentity identity) {
        Long token = identity.getAttribute(ApiTokenIdentityProvider.TOKEN_ID);
        if (token != null && token != 0) {
            return "token:" + token;
        }
        String person = OidcIdentityAugmentor.personKey(identity);
        return person != null ? "user:" + person : "user:" + identity.getPrincipal().getName();
    }

    /**
     * The key as sent, checked: 1 to {@value #MAX_LENGTH} printable characters (ASCII letters,
     * digits, punctuation and spaces). Null when no key was sent.
     *
     * @throws IllegalArgumentException (400) for any other key; the message never quotes it
     */
    public static String check(String key) {
        if (key == null) {
            return null;
        }
        boolean printable = !key.isEmpty() && key.length() <= MAX_LENGTH
            && key.chars().allMatch(ch -> ch >= 0x20 && ch <= 0x7e) && !key.isBlank();
        if (!printable) {
            throw new IllegalArgumentException("Idempotency-Key: 1 to " + MAX_LENGTH + " printable characters "
                + "(letters, digits, punctuation and spaces), the same on every retry of one request");
        }
        return key;
    }

    /**
     * What a start asked for, as a SHA-256 in hex: its kind, name, version (null for the latest) and
     * parameters as given, written the same way whatever their order. A retry must send the same.
     */
    public static String requestHash(String kind, String name, Integer version, Map<String, Object> parameters) {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("kind", kind);
        request.put("name", name);
        request.put("version", version);
        request.put("parameters", parameters == null ? Map.of() : parameters);
        try {
            byte[] text = CANONICAL.writeValueAsBytes(request);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text));
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("parameters: values that cannot be written as JSON", e);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is missing from this Java", e);
        }
    }

    /**
     * The run this caller's {@code key} started in the last 24 hours, read without locking anything;
     * empty when there is none. A caller sending a key looks here first, so a retry finds its run
     * without checking the pipeline or flow again (its latest version may have changed since).
     *
     * @throws WebApplicationException (422) when the key started a different request
     */
    public Optional<Started> find(String caller, String key, String kind, String target, String request) {
        return db.tx(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT kind, target, request_hash, "
                + "coalesce(pipeline_run_id, flow_run_id) FROM kilnmark.run_request_key WHERE caller = ? "
                + "AND request_key = ? AND created_at >= now() - make_interval(secs => ?)")) {
                ps.setString(1, caller);
                ps.setString(2, key);
                ps.setLong(3, KEPT.toSeconds());
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? Optional.of(same(rs, kind, target, request)) : Optional.<Started>empty();
                }
            }
        });
    }

    /**
     * Starts a run once per key: {@code queue} queues a run already checked, in the same transaction
     * as the key, unless this caller's {@code key} started a run in the last 24 hours, whose id is
     * given instead. When {@code queue} throws, nothing is kept, and the same key can try again.
     *
     * <p>Locks are taken in one order, as deleting a pipeline takes them: the pipeline's or flow's row,
     * then the key's. A key older than 24 hours is taken over in place, never deleted here (the
     * clean-up deletes old keys), so a start and a pipeline's delete cannot wait on each other. Only
     * the key's row and the run's tables are touched inside: checking the run, which opens
     * connections of its own, is done before ({@code checkRun}).
     *
     * @param kind {@code pipeline} or {@code flow}
     * @param request {@link #requestHash} of the start
     * @throws WebApplicationException (422) when the key started a different request
     */
    public Started start(String caller, String key, String kind, String target, String request, LongSupplier queue) {
        boolean pipeline = "pipeline".equals(kind);
        return db.together(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT 1 FROM kilnmark."
                + (pipeline ? "pipeline" : "flow") + " WHERE name = ? FOR UPDATE")) {
                ps.setString(1, target);
                ps.executeQuery().close();
            }
            boolean mine;
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO kilnmark.run_request_key "
                + "(caller, request_key, kind, target, request_hash) VALUES (?, ?, ?, ?, ?) "
                + "ON CONFLICT (caller, request_key) DO UPDATE SET kind = EXCLUDED.kind, target = EXCLUDED.target, "
                + "request_hash = EXCLUDED.request_hash, created_at = now(), pipeline_run_id = NULL, flow_run_id = NULL "
                + "WHERE run_request_key.created_at < now() - make_interval(secs => ?)")) {
                ps.setString(1, caller);
                ps.setString(2, key);
                ps.setString(3, kind);
                ps.setString(4, target);
                ps.setString(5, request);
                ps.setLong(6, KEPT.toSeconds());
                // waits here while another request holds the same key; 0 when a live key is there
                mine = ps.executeUpdate() == 1;
            }
            if (!mine) {
                return earlier(c, caller, key, kind, target, request);
            }
            afterKey.run();
            long id = queue.getAsLong(); // joins this transaction: the run commits with its key
            try (PreparedStatement ps = c.prepareStatement("UPDATE kilnmark.run_request_key SET "
                + (pipeline ? "pipeline_run_id" : "flow_run_id") + " = ? WHERE caller = ? AND request_key = ?")) {
                ps.setLong(1, id);
                ps.setString(2, caller);
                ps.setString(3, key);
                ps.executeUpdate();
            }
            afterQueue.run();
            return new Started(id, false);
        });
    }

    /** The run an earlier request with this key started; 422 when that was a different request. */
    private static Started earlier(java.sql.Connection c, String caller, String key, String kind, String target,
                                   String request) throws java.sql.SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT kind, target, request_hash, "
            + "coalesce(pipeline_run_id, flow_run_id) FROM kilnmark.run_request_key WHERE caller = ? AND request_key = ?")) {
            ps.setString(1, caller);
            ps.setString(2, key);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    // its run was deleted a moment ago, and the key with it
                    throw ApiError.of(Response.Status.CONFLICT, "The run this request key started has just been "
                        + "deleted. Send the request again.");
                }
                return same(rs, kind, target, request);
            }
        }
    }

    /** The key's run when the row is for this request; 422 when it is for another. */
    private static Started same(ResultSet rs, String kind, String target, String request) throws java.sql.SQLException {
        if (!kind.equals(rs.getString(1)) || !target.equals(rs.getString(2))) {
            throw unprocessable("This request key was used to start another pipeline or flow in the last 24 hours. "
                + "Use a new key for this run.");
        }
        if (!request.equals(rs.getString(3))) {
            throw unprocessable("This request key was used in the last 24 hours with another version or other "
                + "parameter values. Send the same values to find that run, or use a new key.");
        }
        long id = rs.getLong(4);
        if (rs.wasNull()) {
            throw new IllegalStateException("a request key stored with no run");
        }
        return new Started(id, true);
    }

    private static WebApplicationException unprocessable(String message) {
        return new WebApplicationException(Response.status(422).type(MediaType.APPLICATION_JSON)
            .entity(new ApiError(message)).build());
    }

    /** Tests only: runs {@code hook} inside each keyed start, after its run is queued and before it commits. */
    void afterQueue(Runnable hook) {
        afterQueue = hook == null ? () -> { } : hook;
    }

    /** Tests only: runs {@code hook} inside each keyed start, after its key's row is written and before its run is queued. */
    void afterKey(Runnable hook) {
        afterKey = hook == null ? () -> { } : hook;
    }

    void onStart(@Observes StartupEvent event) {
        if (!config.roles().contains(Role.SCHEDULER)) {
            return;
        }
        thread = Thread.ofVirtual().name("kilnmark-run-request-keys").start(() -> {
            try {
                Thread.sleep(Duration.ofMinutes(1));
                while (!Thread.currentThread().isInterrupted()) {
                    purge();
                    Thread.sleep(EVERY);
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

    /** Deletes keys older than 24 hours; returns how many. Running it on two processes at once is harmless. */
    public int purge() {
        try {
            int n = db.tx(c -> {
                try (PreparedStatement ps = c.prepareStatement(
                    "DELETE FROM kilnmark.run_request_key WHERE created_at < now() - make_interval(secs => ?)")) {
                    ps.setLong(1, KEPT.toSeconds());
                    return ps.executeUpdate();
                }
            });
            if (n > 0) {
                LOG.infof("Deleted %d run request keys older than %s", n, KEPT);
            }
            return n;
        } catch (RuntimeException e) {
            LOG.warnf("Could not delete old run request keys: %s", e.getMessage());
            return 0;
        }
    }
}

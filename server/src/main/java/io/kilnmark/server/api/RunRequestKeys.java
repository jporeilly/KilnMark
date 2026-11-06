package io.kilnmark.server.api;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.util.concurrent.Semaphore;
import java.util.function.LongSupplier;

import org.jboss.logging.Logger;

import io.kilnmark.server.Db;
import io.kilnmark.server.KilnmarkConfig;
import io.kilnmark.server.Role;
import io.kilnmark.server.auth.ApiTokenIdentityProvider;
import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.ws.rs.core.Response;

/**
 * Request keys for starting runs (ADR 0047): the same {@code Idempotency-Key} from the same caller,
 * for the same pipeline or flow, within 24 hours, gives the run the first request started instead
 * of starting another. Stored in {@code kilnmark.run_request_key} (V37). Keys older than 24 hours
 * count as unused, and are deleted shortly after start and then every hour on processes with the
 * scheduler role.
 *
 * <p>Race-safe: the key's row is inserted first, in a transaction kept open while the run is queued
 * (in a transaction of its own) and then set on the row. A second request with the same key waits on
 * the row's primary key until the first commits, then finds its run; if the first could not start a
 * run, its row is rolled back and the second tries itself. At most {@link #AT_ONCE} keyed starts run
 * at once, so the two connections each holds can never use up the pool.
 */
@ApplicationScoped
public class RunRequestKeys {

    private static final Logger LOG = Logger.getLogger(RunRequestKeys.class);
    /** How long a key is kept. */
    public static final Duration KEPT = Duration.ofHours(24);
    /** The longest key. */
    public static final int MAX_LENGTH = 200;
    /** Keyed starts at once on one process. */
    static final int AT_ONCE = 4;
    private static final Duration EVERY = Duration.ofHours(1);

    private final Db db;
    private final KilnmarkConfig config;
    private final Semaphore starting = new Semaphore(AT_ONCE, true);
    private volatile Thread thread;

    public RunRequestKeys(Db db, KilnmarkConfig config) {
        this.db = db;
        this.config = config;
    }

    /** The run a request started, and whether an earlier request with the same key had started it. */
    public record Started(long runId, boolean repeated) {
    }

    /**
     * Whose key space a request is in: its token, or the person signed in with the identity provider
     * (by the name the audit log uses, which no chosen name can imitate).
     */
    public static String caller(SecurityIdentity identity) {
        Long token = identity.getAttribute(ApiTokenIdentityProvider.TOKEN_ID);
        if (token != null && token != 0) {
            return "token:" + token;
        }
        return "user:" + identity.getPrincipal().getName();
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
     * Starts a run once per key: {@code queue} queues it (in its own transaction) and gives its id,
     * unless this caller's {@code key} already started a run of {@code target} in the last 24 hours,
     * whose id is given instead. When {@code queue} throws, nothing is kept, and the same key can try
     * again.
     *
     * @param kind {@code pipeline} or {@code flow}
     * @throws jakarta.ws.rs.WebApplicationException (422) when the key started a run of another
     *     pipeline or flow
     */
    public Started start(String caller, String key, String kind, String target, LongSupplier queue) {
        starting.acquireUninterruptibly();
        try {
            return db.tx(c -> {
                try (PreparedStatement ps = c.prepareStatement("DELETE FROM kilnmark.run_request_key "
                    + "WHERE caller = ? AND request_key = ? AND created_at < now() - make_interval(secs => ?)")) {
                    ps.setString(1, caller);
                    ps.setString(2, key);
                    ps.setLong(3, KEPT.toSeconds());
                    ps.executeUpdate();
                }
                boolean mine;
                try (PreparedStatement ps = c.prepareStatement("INSERT INTO kilnmark.run_request_key "
                    + "(caller, request_key, kind, target) VALUES (?, ?, ?, ?) ON CONFLICT DO NOTHING")) {
                    ps.setString(1, caller);
                    ps.setString(2, key);
                    ps.setString(3, kind);
                    ps.setString(4, target);
                    mine = ps.executeUpdate() == 1; // waits here while another request holds the same key
                }
                if (!mine) {
                    return earlier(c, caller, key, kind, target);
                }
                long id = queue.getAsLong();
                try (PreparedStatement ps = c.prepareStatement("UPDATE kilnmark.run_request_key SET "
                    + ("pipeline".equals(kind) ? "pipeline_run_id" : "flow_run_id")
                    + " = ? WHERE caller = ? AND request_key = ?")) {
                    ps.setLong(1, id);
                    ps.setString(2, caller);
                    ps.setString(3, key);
                    ps.executeUpdate();
                }
                return new Started(id, false);
            });
        } finally {
            starting.release();
        }
    }

    /** The run an earlier request with this key started; 422 when that was of another pipeline or flow. */
    private static Started earlier(java.sql.Connection c, String caller, String key, String kind, String target)
        throws java.sql.SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT kind, target, coalesce(pipeline_run_id, flow_run_id) "
            + "FROM kilnmark.run_request_key WHERE caller = ? AND request_key = ?")) {
            ps.setString(1, caller);
            ps.setString(2, key);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    // its run was deleted a moment ago, and the key with it
                    throw ApiError.of(Response.Status.CONFLICT, "The run this request key started has just been "
                        + "deleted. Send the request again.");
                }
                if (!kind.equals(rs.getString(1)) || !target.equals(rs.getString(2))) {
                    throw new jakarta.ws.rs.WebApplicationException(Response.status(422)
                        .type(jakarta.ws.rs.core.MediaType.APPLICATION_JSON)
                        .entity(new ApiError("This request key was used to start another pipeline or flow in the "
                            + "last 24 hours. Use a new key for this run.")).build());
                }
                long id = rs.getLong(3);
                if (rs.wasNull()) {
                    throw new IllegalStateException("a request key stored with no run");
                }
                return new Started(id, true);
            }
        }
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

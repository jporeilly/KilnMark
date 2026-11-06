package io.kilnmark.server.jobs;

import java.util.Optional;
import java.util.Set;

import io.agroal.api.AgroalDataSource;
import io.kilnmark.server.KilnmarkConfig;
import io.kilnmark.server.Role;
import io.quarkus.runtime.StartupEvent;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;

/**
 * Stops the server at start when its pool of connections to Kilnmark's own database is smaller than
 * the work this process takes on (docs/design/parallel-runs.md, P1; INSTALL.md, "Sizing the
 * workers"). A worker's running job holds one connection for its lease and uses one or two more
 * while it works, each flow it watches holds one, and the worker keeps one for {@code LISTEN}; the
 * API and the scheduler need about five between them. A pool smaller than that starts and works
 * while little runs, then fails under load: requests answer 500 and jobs fail when no connection
 * comes within the pool's wait. An operator would rather be told at start, with the settings to
 * change, than find out from a failed night's runs, so this refuses to start rather than warn.
 *
 * <p>It runs after the database check and migrations ({@link io.kilnmark.server.DatabaseTls}), and
 * before the worker starts taking jobs.
 */
@ApplicationScoped
public class PoolSizeCheck {

    /** Connections the API and the scheduler need between them, on a process with either role. */
    static final int API_AND_SCHEDULER = 5;

    private final KilnmarkConfig config;
    private final AgroalDataSource source;

    public PoolSizeCheck(KilnmarkConfig config, AgroalDataSource source) {
        this.config = config;
        this.source = source;
    }

    void onStart(@Observes @Priority(2) StartupEvent event) {
        int poolMax = source.getConfiguration().connectionPoolConfiguration().maxSize();
        problem(config.roles(), config.worker().concurrency(), config.worker().flows(), poolMax).ifPresent(p -> {
            throw new IllegalStateException(p);
        });
    }

    /**
     * Connections a process with these roles needs at least: {@code concurrency × 3 + flows + 1} for
     * the worker role (the 1 for listening), and {@value #API_AND_SCHEDULER} more with the api or
     * scheduler role. Concurrency and flows count as at least 1, as the worker reads them.
     */
    static int needed(Set<Role> roles, int concurrency, int flows) {
        int n = 0;
        if (roles.contains(Role.WORKER)) {
            n += Math.max(1, concurrency) * 3 + Math.max(1, flows) + 1;
        }
        if (roles.contains(Role.API) || roles.contains(Role.SCHEDULER)) {
            n += API_AND_SCHEDULER;
        }
        return n;
    }

    /** Why the pool is too small for this process, in words naming the settings; empty when it is not. */
    static Optional<String> problem(Set<Role> roles, int concurrency, int flows, int poolMax) {
        int needed = needed(roles, concurrency, flows);
        if (poolMax >= needed) {
            return Optional.empty();
        }
        String worker = roles.contains(Role.WORKER)
            ? " With the worker role it needs kilnmark.worker.concurrency (" + Math.max(1, concurrency) + ") × 3 + "
                + "kilnmark.worker.flows (" + Math.max(1, flows) + ") + 1"
                + (roles.contains(Role.API) || roles.contains(Role.SCHEDULER) ? ", plus " + API_AND_SCHEDULER
                    + " for the API and scheduler." : ".")
            : " It needs " + API_AND_SCHEDULER + " for the API and scheduler.";
        return Optional.of("The pool of connections to Kilnmark's database is too small for this process: "
            + "quarkus.datasource.jdbc.max-size is " + poolMax + ", and it needs at least " + needed + "." + worker
            + " Raise QUARKUS_DATASOURCE_JDBC_MAX_SIZE to " + needed + " or more (and PostgreSQL's "
            + "max_connections to the sum of every Kilnmark process's pool)"
            + (roles.contains(Role.WORKER) ? ", or lower KILNMARK_WORKER_CONCURRENCY or KILNMARK_WORKER_FLOWS" : "")
            + ". INSTALL.md, \"Sizing the workers\", says how.");
    }
}

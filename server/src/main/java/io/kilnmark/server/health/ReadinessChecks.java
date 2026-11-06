package io.kilnmark.server.health;

import java.sql.Connection;

import org.eclipse.microprofile.health.HealthCheck;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.eclipse.microprofile.health.Liveness;
import org.eclipse.microprofile.health.Readiness;
import org.flywaydb.core.Flyway;
import org.jboss.logging.Logger;

import io.agroal.api.AgroalDataSource;
import io.kilnmark.server.KilnmarkConfig;
import io.kilnmark.server.Role;
import io.kilnmark.server.jobs.JobWorker;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;

/**
 * Health for orchestrators (Compose, Kubernetes), on SmallRye Health's public endpoints:
 * {@code /q/health/ready} is up when the database answers, every migration is applied and, in a
 * process with the worker role, the worker is taking jobs and, in one with the scheduler role, the
 * scheduler is looking for due schedules (ADR 0046); {@code /q/health/live} is up while the
 * process runs. Each check reports only its name and UP or DOWN: no host, user or error text,
 * since anyone who can reach the server can read them. Why a check is down goes to the log.
 */
@ApplicationScoped
public class ReadinessChecks {

    private static final Logger LOG = Logger.getLogger(ReadinessChecks.class);

    private final AgroalDataSource db;
    private final Instance<Flyway> flyway;
    private final JobWorker worker;
    private final KilnmarkConfig config;
    /** The scheduler's loop, for its check. */
    @jakarta.inject.Inject
    io.kilnmark.server.schedules.Scheduler scheduler;
    /** Migrations cannot be undone while the server runs, so once applied this stays true. */
    private volatile boolean migrated;

    public ReadinessChecks(AgroalDataSource db, Instance<Flyway> flyway, JobWorker worker, KilnmarkConfig config) {
        this.db = db;
        this.flyway = flyway;
        this.worker = worker;
        this.config = config;
    }

    boolean databaseUp() {
        try (Connection c = db.getConnection()) {
            return c.isValid(3);
        } catch (Exception e) {
            LOG.warnf("Readiness: the database is not reachable: %s", e.getMessage());
            return false;
        }
    }

    boolean migrationsApplied() {
        if (migrated) {
            return true;
        }
        try {
            migrated = flyway.get().info().pending().length == 0;
            if (!migrated) {
                LOG.warn("Readiness: database migrations are not all applied yet");
            }
            return migrated;
        } catch (Exception e) {
            LOG.warnf("Readiness: cannot read the migration history: %s", e.getMessage());
            return false;
        }
    }

    boolean workerUp() {
        return !config.roles().contains(Role.WORKER) || worker.running();
    }

    boolean schedulerUp() {
        boolean up = scheduler == null || scheduler.running();
        if (!up) {
            LOG.warn("Readiness: the scheduler has not looked for due schedules within three poll intervals");
        }
        return up;
    }

    private static HealthCheckResponse response(String name, boolean up) {
        return HealthCheckResponse.named(name).status(up).build();
    }

    @Readiness
    @ApplicationScoped
    public static class Database implements HealthCheck {
        private final ReadinessChecks checks;

        public Database(ReadinessChecks checks) {
            this.checks = checks;
        }

        @Override
        public HealthCheckResponse call() {
            return response("database", checks.databaseUp());
        }
    }

    @Readiness
    @ApplicationScoped
    public static class Migrations implements HealthCheck {
        private final ReadinessChecks checks;

        public Migrations(ReadinessChecks checks) {
            this.checks = checks;
        }

        @Override
        public HealthCheckResponse call() {
            return response("migrations", checks.migrationsApplied());
        }
    }

    /** Up in a process without the worker role, which never takes jobs. */
    @Readiness
    @ApplicationScoped
    public static class Worker implements HealthCheck {
        private final ReadinessChecks checks;

        public Worker(ReadinessChecks checks) {
            this.checks = checks;
        }

        @Override
        public HealthCheckResponse call() {
            return response("worker", checks.workerUp());
        }
    }

    /** Up in a process without the scheduler role, which never fires schedules. */
    @Readiness
    @ApplicationScoped
    public static class SchedulerCheck implements HealthCheck {
        private final ReadinessChecks checks;

        public SchedulerCheck(ReadinessChecks checks) {
            this.checks = checks;
        }

        @Override
        public HealthCheckResponse call() {
            return response("scheduler", checks.schedulerUp());
        }
    }

    /** The process is running and answering requests; nothing outside it is checked. */
    @Liveness
    @ApplicationScoped
    public static class Alive implements HealthCheck {
        @Override
        public HealthCheckResponse call() {
            return response("server", true);
        }
    }
}

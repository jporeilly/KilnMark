package io.kilnmark.server;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;

/** Kilnmark's own settings, under the {@code kilnmark.} prefix. */
@ConfigMapping(prefix = "kilnmark")
public interface KilnmarkConfig {

    /** Roles this process runs, for example {@code KILNMARK_ROLES=worker}. */
    @WithDefault("api,scheduler,worker")
    Set<Role> roles();

    /** Key for stored source passwords, base64 of 32 bytes. Without it, the key file is used. */
    Optional<String> secretKey();

    /** Where the key lives when {@link #secretKey()} is not set; created on first start. */
    String secretKeyFile();

    /** Folder holding secret files that bulk import may refer to as {@code secret:NAME}. */
    @WithDefault("/run/secrets")
    String secretsDir();

    /**
     * An admin API token taken from the environment ({@code KILNMARK_ADMIN_TOKEN}): {@code km_}
     * followed by at least 43 random base64url characters. It is stored hashed on start and stops
     * working when the variable is removed or changed.
     */
    Optional<String> adminToken();

    Auth auth();

    Scan scan();

    Worker worker();

    /** Settings for the {@code files} connector and Parquet reading. */
    Files files();

    /** Settings for the {@code s3} connector. */
    S3 s3();

    /** Managing sources: connection tests, container discovery and bulk import. */
    Sources sources();

    /** API sign-in. */
    interface Auth {
        /**
         * {@code tokens} (the default) or {@code off}. Off makes every caller an admin and is only
         * allowed when the server listens on a loopback address. Any other value stops the server.
         */
        @WithDefault("tokens")
        io.kilnmark.server.auth.AuthMode mode();
    }

    /** The worker role: how it pulls jobs from the queue. */
    interface Worker {
        /** Jobs (scans) one worker runs at the same time. */
        @WithDefault("2")
        int concurrency();

        /** How often to look for jobs when no notification arrives. */
        @WithDefault("5s")
        Duration pollInterval();

        /** How often a running job's heartbeat is refreshed. */
        @WithDefault("10s")
        Duration heartbeatInterval();

        /** A running job whose heartbeat is older than this, and whose worker is gone, is recovered. */
        @WithDefault("2m")
        Duration staleAfter();

        /** Times a job is tried before it is marked failed. */
        @WithDefault("3")
        int maxAttempts();

        /** On shutdown, how long running jobs may take to finish before they are stopped and requeued. */
        @WithDefault("30s")
        Duration shutdownTimeout();
    }

    interface Files {
        /**
         * Folders that sources of kind {@code files} may read, comma-separated; a source's folder
         * must be one of them or inside one. None (the default) disables folder sources.
         */
        Optional<List<String>> roots();

        /** Threads decoding Parquet, shared by every read. */
        @WithDefault("2")
        int parquetThreads();

        /** The most read from a Parquet file in one request, in MiB. */
        @WithDefault("8")
        int parquetChunkMb();

        /** Where native compression libraries are unpacked; default {@code ~/.kilnmark/native}. */
        Optional<String> nativeDir();
    }

    interface S3 {
        /**
         * Whether sources on AWS may leave out an access key and use this server's own AWS
         * identity. Off by default: anyone who can add a source would get the server's access.
         */
        @WithDefault("false")
        boolean allowDefaultCredentials();
    }

    interface Scan {
        /** Tables profiled at the same time within one scan. */
        @WithDefault("2")
        int parallelTables();

        /** Rows read from each view; views can be expensive to run in full. 0 reads every row. */
        @WithDefault("100000")
        long viewRowLimit();

        /** Arrow memory one table's pipeline may hold. */
        @WithDefault("512")
        long memoryLimitMb();
    }

    /** Managing sources. */
    interface Sources {
        /** How long a connection test or a container listing may take before it is reported as too slow. */
        @WithDefault("15s")
        Duration checkTimeout();

        /** Connection tests and container listings people may run at the same time, across the server. */
        @WithDefault("4")
        int checkConcurrency();

        /**
         * Host names bulk import may replace when asked to (its {@code remapHosts} option), as
         * {@code from=to} pairs, comma-separated, for example
         * {@code localhost=host.docker.internal,127.0.0.1=host.docker.internal}: sources written from
         * a desktop's point of view then work from inside a container. None by default.
         */
        Optional<List<String>> hostMap();

        /**
         * The hosts sources may connect to: host names or addresses, or {@code *.example.com} for a
         * whole domain, comma-separated. Applied to database URLs and S3 endpoints whenever a source
         * is added, edited, imported or checked. None (the default) allows any host.
         */
        Optional<List<String>> allowedHosts();
    }
}

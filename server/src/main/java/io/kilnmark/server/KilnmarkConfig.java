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

    /** The AI runtime: model calls, embeddings and search by meaning (ADR 0016). */
    Ai ai();

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

    /** The AI runtime (ADR 0016). Models themselves are added at run time, as model profiles. */
    interface Ai {
        /**
         * {@code allowed} (the default) or {@code forbidden}. Forbidden locks out every model that
         * is not local, whatever an admin sets in the UI: for servers whose metadata must never
         * leave the network. Any other value stops the server.
         */
        @WithDefault("allowed")
        HostedModels hostedModels();

        /**
         * While hosted models are forbidden, the hosts and networks (CIDR) a model marked local
         * must be on. Unset: loopback and private networks (10.0.0.0/8, 172.16.0.0/12,
         * 192.168.0.0/16, fc00::/7). Set it to narrow them, for example {@code 10.20.0.0/16,ai}.
         */
        Optional<List<String>> localHosts();

        /**
         * Lets model profiles use link-local addresses (169.254.0.0/16, fe80::/10) and cloud
         * metadata names, which are refused by default so a profile cannot reach a cloud
         * provider's metadata service.
         */
        @WithDefault("false")
        boolean allowMetadataAddresses();

        /** Retries after a failed model call (timeouts, rate limits, server errors), with backoff. */
        @WithDefault("2")
        int maxRetries();

        /** Assets sent to the embedding model in one request. */
        @WithDefault("16")
        int embedBatchSize();

        /** Search by meaning ignores assets less similar than this (cosine similarity, 0 to 1). */
        @WithDefault("0.3")
        double minSimilarity();

        /** Keep prompt and answer text in usage records. Off: only counts, times and outcomes. */
        @WithDefault("false")
        boolean keepUsageText();

        /** How long usage records are kept. Older ones are deleted every few hours. */
        @WithDefault("90d")
        Duration usageRetention();

        /** {@code kilnmark.ai.hosted-models}. */
        enum HostedModels {
            ALLOWED, FORBIDDEN
        }
    }
}

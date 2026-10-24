package io.kilnmark.server;

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

    Scan scan();

    /** Settings for the {@code files} connector and Parquet reading. */
    Files files();

    /** Settings for the {@code s3} connector. */
    S3 s3();

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
}

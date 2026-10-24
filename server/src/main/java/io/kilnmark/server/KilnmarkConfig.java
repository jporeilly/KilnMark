package io.kilnmark.server;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;
import java.util.Set;

/** Server settings, read from application.properties or KILNMARK_* environment variables. */
@ConfigMapping(prefix = "kilnmark")
public interface KilnmarkConfig {

    /** Roles this process runs, for example {@code KILNMARK_ROLES=worker}. */
    @WithDefault("api,scheduler,worker")
    Set<Role> roles();
}

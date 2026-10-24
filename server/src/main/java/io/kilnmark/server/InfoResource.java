package io.kilnmark.server;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import java.util.Set;
import java.util.TreeSet;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/** Reports what this server is and which roles it runs. */
@Path("/api/v1/info")
public class InfoResource {

    /** Response body for {@code GET /api/v1/info}. */
    public record Info(String product, String version, Set<Role> roles) {}

    private final KilnmarkConfig config;
    private final String version;

    public InfoResource(KilnmarkConfig config,
                        @ConfigProperty(name = "quarkus.application.version") String version) {
        this.config = config;
        this.version = version;
    }

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public Info info() {
        return new Info("Kilnmark", version, new TreeSet<>(config.roles()));
    }
}

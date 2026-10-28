package io.kilnmark.server.api;

import java.util.List;

import io.kilnmark.server.ai.Callers;
import io.kilnmark.server.auth.AccessRole;
import io.kilnmark.server.drivers.DriverRegistry;
import io.kilnmark.server.drivers.DriverService;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.annotation.security.RolesAllowed;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

/**
 * Drivers the customer installs, for admins (Settings › Drivers): which connectors need which
 * driver, whether it is installed, its licence, and accepting the licence so the server downloads
 * it (docs/design/drivers.md). Under {@code registered} and {@code files}, the JDBC drivers an admin
 * registers for the generic JDBC connector (Settings › JDBC drivers): uploading a jar to the drivers
 * folder, registering it (its checksum is recorded then), the audit trail, and removing one.
 */
@Path("/api/v1/drivers")
@Produces(MediaType.APPLICATION_JSON)
@RolesAllowed(AccessRole.ADMIN_ROLE)
public class DriverResource {

    private final DriverService drivers;
    private final DriverRegistry registry;
    private final SecurityIdentity identity;

    public DriverResource(DriverService drivers, DriverRegistry registry, SecurityIdentity identity) {
        this.drivers = drivers;
        this.registry = registry;
        this.identity = identity;
    }

    @GET
    public List<DriverService.Item> list() {
        return drivers.list();
    }

    /**
     * Accepts the driver's licence on the organisation's behalf and has the server download and
     * verify it. 502 with what to do when the download did not install it; 409 when this server
     * does not download drivers.
     */
    @POST
    @Path("{id}/accept")
    public DriverService.Item accept(@PathParam("id") String id) {
        try {
            return drivers.accept(id, Callers.of(identity))
                .orElseThrow(() -> ApiError.of(Response.Status.NOT_FOUND, "No connector needs a driver called " + id));
        } catch (DriverService.DownloadFailed e) {
            throw ApiError.of(e.reason() == DriverService.DownloadFailed.Reason.DOWNLOADS_OFF
                ? Response.Status.CONFLICT : Response.Status.BAD_GATEWAY, e.getMessage());
        }
    }

    /** The registered JDBC drivers, with whether each jar is there and unchanged, and the sources using it. */
    @GET
    @Path("registered")
    public List<DriverRegistry.Item> registered() {
        return registry.list();
    }

    /**
     * Registers a jar in the drivers folder as a JDBC driver: {@code name}, {@code fileName},
     * {@code driverClass}, {@code urlPrefix}. Its SHA-256 is recorded now; the driver loads only
     * while the jar keeps it. 400 when the jar or the class is not right; 409 when the name is taken.
     */
    @POST
    @Path("registered")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response register(DriverRegistry.Registration body) {
        try {
            return Response.status(Response.Status.CREATED).entity(registry.register(body, Callers.of(identity)))
                .build();
        } catch (DriverRegistry.Refused e) {
            throw refused(e);
        }
    }

    /** Removes a registration (409 while sources use it); the jar stays in the folder. */
    @DELETE
    @Path("registered/{name}")
    public Response remove(@PathParam("name") String name) {
        try {
            registry.remove(name, Callers.of(identity));
            return Response.noContent().build();
        } catch (DriverRegistry.Refused e) {
            throw refused(e);
        }
    }

    /** Who registered and removed which JDBC drivers, and when, newest first. */
    @GET
    @Path("registered/history")
    public List<DriverRegistry.LogEntry> history(@QueryParam("limit") Integer limit) {
        return registry.history(limit == null ? 50 : limit);
    }

    /** The jars in the drivers folder, for picking one to register. */
    @GET
    @Path("files")
    public DriverRegistry.Folder files() {
        return registry.files();
    }

    /**
     * Uploads a jar into the drivers folder under {@code fileName}, as the request's body; never over
     * a file already there (409). Registering it is a separate step.
     */
    @POST
    @Path("files/{fileName}")
    @Consumes(MediaType.APPLICATION_OCTET_STREAM)
    public Response upload(@PathParam("fileName") String fileName, java.io.InputStream body) {
        try {
            return Response.status(Response.Status.CREATED).entity(registry.upload(fileName, body,
                Callers.of(identity))).build();
        } catch (DriverRegistry.Refused e) {
            throw refused(e);
        }
    }

    private static jakarta.ws.rs.WebApplicationException refused(DriverRegistry.Refused e) {
        return ApiError.of(switch (e.reason()) {
            case INVALID -> Response.Status.BAD_REQUEST;
            case CONFLICT -> Response.Status.CONFLICT;
            case NOT_FOUND -> Response.Status.NOT_FOUND;
        }, e.getMessage());
    }
}

package io.kilnmark.server.api;

import java.util.List;

import io.kilnmark.server.ai.Callers;
import io.kilnmark.server.auth.AccessRole;
import io.kilnmark.server.drivers.DriverService;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.annotation.security.RolesAllowed;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

/**
 * Drivers the customer installs, for admins (Settings › Drivers): which connectors need which
 * driver, whether it is installed, its licence, and accepting the licence so the server downloads
 * it (docs/design/drivers.md).
 */
@Path("/api/v1/drivers")
@Produces(MediaType.APPLICATION_JSON)
@RolesAllowed(AccessRole.ADMIN_ROLE)
public class DriverResource {

    private final DriverService drivers;
    private final SecurityIdentity identity;

    public DriverResource(DriverService drivers, SecurityIdentity identity) {
        this.drivers = drivers;
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
}

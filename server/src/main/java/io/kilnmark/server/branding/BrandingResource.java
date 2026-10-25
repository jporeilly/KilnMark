package io.kilnmark.server.branding;

import jakarta.annotation.security.PermitAll;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.CacheControl;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

/**
 * The operator's branding (docs/guide/branding.md): the organisation's name and logo, and logos for
 * connectors, from the branding folder.
 *
 * <p>Public, no token needed: the sign-in page shows the organisation's name and logo before anyone
 * has signed in, and images are shown with {@code <img>}, which cannot send the API token. Nothing
 * here is private: only files the operator lists in the manifest are served, and the JSON holds
 * names, colours and links.
 *
 * <p>Images are served so nothing in them can run: a content type fixed by the checked extension,
 * {@code nosniff}, and a Content-Security-Policy that allows no scripts, no loads and sandboxes the
 * document, so an SVG opened directly in a tab is inert too. The UI shows them only with
 * {@code <img>}, never inline.
 */
@Path("/api/v1/branding")
@PermitAll
public class BrandingResource {

    /** Inline styles are common in SVGs and harmless; nothing else is allowed. */
    static final String IMAGE_CSP = "default-src 'none'; style-src 'unsafe-inline'; sandbox";

    private final BrandingFolder folder;

    public BrandingResource(BrandingFolder folder) {
        this.folder = folder;
    }

    /** The product name, logo and connector looks; empty fields when no branding is set. */
    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public Response branding() {
        CacheControl cache = new CacheControl();
        cache.setNoCache(true);
        return Response.ok(folder.view()).cacheControl(cache).header("X-Content-Type-Options", "nosniff").build();
    }

    /** One image named in the manifest; 404 for any other name. */
    @GET
    @Path("files/{name}")
    public Response file(@PathParam("name") String name, @HeaderParam("If-None-Match") String ifNoneMatch) {
        return folder.image(name).map(i -> image(i, ifNoneMatch)).orElseGet(() -> Response
            .status(Response.Status.NOT_FOUND).type(MediaType.APPLICATION_JSON)
            .entity(new io.kilnmark.server.api.ApiError("No branding image " + name))
            .header("X-Content-Type-Options", "nosniff").build());
    }

    static Response image(BrandingFolder.Image i, String ifNoneMatch) {
        String etag = "\"" + i.etag() + "\"";
        CacheControl cache = new CacheControl();
        cache.setMaxAge(300);
        Response.ResponseBuilder r = etag.equals(ifNoneMatch) ? Response.notModified()
            : Response.ok(i.bytes(), i.contentType());
        return r.cacheControl(cache)
            .header("ETag", etag)
            .header("X-Content-Type-Options", "nosniff")
            .header("Content-Security-Policy", IMAGE_CSP)
            .header("Cross-Origin-Resource-Policy", "same-origin")
            .header("Content-Disposition", "inline; filename=\"" + i.name() + "\"")
            .build();
    }
}

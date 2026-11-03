package io.kilnmark.server;

import io.quarkus.vertx.http.runtime.filters.Filters;
import io.vertx.core.MultiMap;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;

/**
 * The headers every response carries (ADR 0031), so a browser runs only Kilnmark's own code on its
 * pages, never shows them inside another site, sends no address on to other sites, and, over HTTPS,
 * keeps to HTTPS. A bank's scanner checks for each of them.
 */
@ApplicationScoped
public class SecurityHeaders {

    /** Only Kilnmark's own files run or load: no inline script or style, nothing from elsewhere. */
    static final String CSP = "default-src 'self'; script-src 'self'; style-src 'self'; "
        + "img-src 'self' data: blob:; font-src 'self'; connect-src 'self'; object-src 'none'; base-uri 'none'; "
        + "frame-ancestors 'none'; form-action 'self'";

    /**
     * The API's own page (/api/docs) starts itself with inline script and style it cannot do without. It
     * shows only the API's description, and calls take a token typed into it.
     */
    static final String API_DOCS_CSP = CSP.replace("script-src 'self'", "script-src 'self' 'unsafe-inline'")
        .replace("style-src 'self'", "style-src 'self' 'unsafe-inline'");

    /** A year, and every name under this one, as scanners expect. */
    static final String HSTS = "max-age=31536000; includeSubDomains";

    @Inject
    KilnmarkConfig config;

    void register(@Observes Filters filters) {
        String hsts = config == null ? "when-https" : config.http().hsts().trim().toLowerCase(java.util.Locale.ROOT);
        String plain = config == null ? "enabled" : config.http().plain().trim().toLowerCase(java.util.Locale.ROOT);
        int httpsPort = config == null ? 8443 : config.http().httpsPort();
        filters.register(rc -> {
            String path = rc.normalizedPath();
            boolean https = "https".equalsIgnoreCase(rc.request().scheme());
            rc.addHeadersEndHandler(v -> set(rc.response().headers(), path, https, hsts));
            // The connection itself, never X-Forwarded-* (which a client can write, and which Quarkus's
            // proxy forwarding puts in the request's own address and scheme): no header claims HTTPS or
            // this machine past the redirect.
            var connection = rc.request().connection();
            String remote = connection.remoteAddress() == null ? null : connection.remoteAddress().host();
            String host = rc.request().authority() == null ? null : rc.request().authority().host();
            switch (plainAnswer(connection.isSsl(), local(remote), plain)) {
                case "redirect" -> {
                    rc.response().setStatusCode(308).putHeader("Location", httpsUrl(host, httpsPort, rc.request().uri()))
                        .end();
                    return;
                }
                case "refuse" -> {
                    rc.response().setStatusCode(403).putHeader("Content-Type", "text/plain")
                        .end("Kilnmark is reached over HTTPS only.");
                    return;
                }
                default -> rc.next();
            }
        }, 20);
    }

    /** Whether an address is this machine's own: the health check, or a proxy beside Kilnmark. */
    static boolean local(String address) {
        return address != null && (address.equals("127.0.0.1") || address.equals("::1")
            || address.equals("0:0:0:0:0:0:0:1") || address.startsWith("127."));
    }

    /**
     * What a request gets under {@code plain}: {@code serve}, {@code redirect} or {@code refuse}.
     * Requests over HTTPS, and plain ones from this machine, are always served.
     */
    static String plainAnswer(boolean https, boolean local, String plain) {
        if (https || local) {
            return "serve";
        }
        return switch (plain) {
            case "redirect" -> "redirect";
            case "disabled" -> "refuse";
            default -> "serve";
        };
    }

    /** The same address over HTTPS: the host the browser used (never another), on the HTTPS port. */
    static String httpsUrl(String host, int port, String uri) {
        String h = host == null || host.isBlank() ? "localhost" : host.contains(":") && !host.startsWith("[")
            ? "[" + host + "]" : host;
        return "https:" + "/" + "/" + h + (port == 443 ? "" : ":" + port) + (uri == null || uri.isEmpty() ? "/" : uri);
    }

    /** The headers for a response to {@code path}, over HTTPS or not, under the HSTS setting given. */
    static void set(MultiMap h, String path, boolean https, String hsts) {
        h.set("Content-Security-Policy", path.startsWith("/api/docs") ? API_DOCS_CSP : CSP);
        h.set("X-Content-Type-Options", "nosniff");
        h.set("X-Frame-Options", "DENY");
        h.set("Referrer-Policy", "no-referrer");
        h.set("Permissions-Policy", "camera=(), microphone=(), geolocation=(), payment=(), usb=()");
        h.set("Cross-Origin-Opener-Policy", "same-origin");
        h.set("Cross-Origin-Resource-Policy", "same-origin");
        if (sendsHsts(https, hsts)) {
            h.set("Strict-Transport-Security", HSTS);
        }
    }

    /** Whether to send HSTS: over HTTPS by default, on every response with {@code always}, never with {@code never}. */
    static boolean sendsHsts(boolean https, String hsts) {
        return switch (hsts) {
            case "always" -> true;
            case "never" -> false;
            default -> https;
        };
    }
}

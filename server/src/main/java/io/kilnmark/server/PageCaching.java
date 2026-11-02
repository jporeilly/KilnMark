package io.kilnmark.server;

import io.quarkus.vertx.http.runtime.filters.Filters;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;

/**
 * The web app's page is checked with the server on every load (no-cache), so a browser takes up a
 * new release at once; the files it loads have their content's hash in their names, so they keep the
 * long, immutable caching every static file gets. Without this the page itself was cached for a day,
 * and a browser went on loading the old release's files after an upgrade.
 */
@ApplicationScoped
public class PageCaching {

    void register(@Observes Filters filters) {
        filters.register(rc -> {
            String path = rc.normalizedPath();
            if (path.equals("/") || path.equals("/index.html")) {
                rc.addHeadersEndHandler(v -> rc.response().headers().set("Cache-Control", "no-cache"));
            }
            rc.next();
        }, 10);
    }
}

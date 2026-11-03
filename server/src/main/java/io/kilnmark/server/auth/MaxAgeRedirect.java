package io.kilnmark.server.auth;

import io.kilnmark.server.KilnmarkConfig;
import io.quarkus.oidc.OidcRedirectFilter;
import io.quarkus.oidc.Redirect;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * Asks the identity provider, on every sign-in, to make the person sign in again when their last
 * sign-in there is older than Kilnmark's longest session ({@code max_age}, OpenID Connect Core
 * 3.1.2.1). Without it, a provider session older than that would send them straight back with the
 * same old sign-in, which Kilnmark refuses, again and again (ADR 0036).
 */
@ApplicationScoped
@io.quarkus.arc.Unremovable // found by the OIDC client at run time, so never referenced by name
@Redirect(Redirect.Location.OIDC_AUTHORIZATION)
public class MaxAgeRedirect implements OidcRedirectFilter {

    private final KilnmarkConfig config;

    public MaxAgeRedirect(KilnmarkConfig config) {
        this.config = config;
    }

    @Override
    public void filter(OidcRedirectContext context) {
        context.additionalQueryParams().set("max_age", Long.toString(config.auth().oidc().maxSession().toSeconds()));
    }
}

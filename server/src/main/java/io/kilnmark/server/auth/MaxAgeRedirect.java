package io.kilnmark.server.auth;

import io.kilnmark.server.KilnmarkConfig;
import io.quarkus.oidc.OidcRedirectFilter;
import io.quarkus.oidc.Redirect;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * Asks the identity provider, on every sign-in, to make the person sign in again when their last
 * sign-in there is older than Kilnmark's longest session ({@code max_age}, OpenID Connect Core
 * 3.1.2.1). Without it, a provider session older than that would send them straight back with the
 * same old sign-in, which Kilnmark refuses, again and again (ADR 0036). When the person asked to sign
 * in again after a refusal ({@link #AFRESH}), also {@code prompt=login}.
 */
@ApplicationScoped
@io.quarkus.arc.Unremovable // found by the OIDC client at run time, so never referenced by name
@Redirect(Redirect.Location.OIDC_AUTHORIZATION)
public class MaxAgeRedirect implements OidcRedirectFilter {

    /** The query parameter on {@code /api/v1/auth/login} that asks the provider for a fresh sign-in. */
    public static final String AFRESH = "afresh";

    private final KilnmarkConfig config;

    public MaxAgeRedirect(KilnmarkConfig config) {
        this.config = config;
    }

    @Override
    public void filter(OidcRedirectContext context) {
        context.additionalQueryParams().set("max_age", Long.toString(config.auth().oidc().maxSession().toSeconds()));
        // After a sign-in given no role, the person asked to sign in again: the provider must ask them,
        // not answer from its own session with the same sign-in.
        if (context.routingContext().request().getParam(AFRESH) != null) {
            context.additionalQueryParams().set("prompt", "login");
        }
    }
}

package io.kilnmark.server.ai;

import io.kilnmark.server.auth.ApiTokenIdentityProvider;
import io.quarkus.security.identity.SecurityIdentity;

/** Who made a request, as usage records and audit columns name them: {@code token 12 (name)}. */
public final class Callers {

    private Callers() {
    }

    public static String of(SecurityIdentity identity) {
        if (identity == null || identity.isAnonymous()) {
            return "anonymous";
        }
        Long id = identity.getAttribute(ApiTokenIdentityProvider.TOKEN_ID);
        String name = identity.getPrincipal().getName().replaceAll("\\p{Cntrl}", "?");
        return id == null || id == 0 ? name : "token " + id + " (" + name + ")";
    }
}

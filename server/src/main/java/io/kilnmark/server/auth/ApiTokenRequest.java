package io.kilnmark.server.auth;

import io.quarkus.security.identity.request.BaseAuthenticationRequest;

/** A bearer token taken from a request's {@code Authorization} header, to be checked by {@link ApiTokenIdentityProvider}. */
public final class ApiTokenRequest extends BaseAuthenticationRequest {

    private final String token;

    public ApiTokenRequest(String token) {
        this.token = token;
    }

    public String token() {
        return token;
    }

    @Override
    public String toString() {
        return "ApiTokenRequest[token hidden]";
    }
}

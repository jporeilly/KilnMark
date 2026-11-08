package io.kilnmark.server.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.Set;

import org.eclipse.microprofile.jwt.JsonWebToken;
import org.junit.jupiter.api.Test;

import io.quarkus.oidc.IdTokenCredential;
import io.quarkus.security.identity.SecurityIdentity;
import io.quarkus.security.runtime.QuarkusSecurityIdentity;
import jakarta.ws.rs.WebApplicationException;

/**
 * Settings › Notifications says why someone has no choices, truly: a person who signs in through the
 * identity provider is told that such sign-ins get no notifications yet, never that sign-in is off.
 */
class NotificationChoicesUnitTest {

    @Test
    void aProviderSignInIsToldItGetsNoNotificationsYet() {
        String said = NotificationResource.unavailable(true);
        assertTrue(said.startsWith("People who sign in through your identity provider get no notifications yet."), said);
        assertFalse(said.contains("Sign-in is off"), said);
        assertTrue(NotificationResource.unavailable(false).startsWith("Sign-in is off on this server"));
    }

    @Test
    void savingChoicesAsAProviderSignInIsRefusedForThatReason() {
        // Refused before anything is read or stored, so no store or mail is needed.
        NotificationResource r = new NotificationResource(null, null, provider());
        WebApplicationException e = assertThrows(WebApplicationException.class, () -> r.saveMine(null));
        assertEquals(409, e.getResponse().getStatus());
        String said = ((ApiError) e.getResponse().getEntity()).error();
        assertTrue(said.startsWith(NotificationResource.PROVIDER_NONE), said);
        assertFalse(said.contains("Sign-in is off"), said);

        NotificationResource off = new NotificationResource(null, null, QuarkusSecurityIdentity.builder()
            .setPrincipal(() -> "anonymous admin").build());
        String offSaid = ((ApiError) assertThrows(WebApplicationException.class, () -> off.saveMine(null))
            .getResponse().getEntity()).error();
        assertTrue(offSaid.startsWith("Sign-in is off on this server"), offSaid);
    }

    /** Someone signed in through the identity provider: an ID token as the principal, and its credential. */
    private static SecurityIdentity provider() {
        Map<String, Object> claims = Map.of("sub", "2f6c1d0e", "preferred_username", "dana.reyes@kilnmark.com");
        JsonWebToken token = new JsonWebToken() {
            @Override
            public String getName() {
                return "dana.reyes@kilnmark.com";
            }

            @Override
            public Set<String> getClaimNames() {
                return claims.keySet();
            }

            @Override
            @SuppressWarnings({"unchecked", "TypeParameterUnusedInFormals"})
            public <T> T getClaim(String name) {
                return (T) claims.get(name);
            }
        };
        return QuarkusSecurityIdentity.builder().setPrincipal(token).addCredential(new IdTokenCredential("id-token"))
            .build();
    }
}

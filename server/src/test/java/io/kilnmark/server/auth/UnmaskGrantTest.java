package io.kilnmark.server.auth;

import static io.kilnmark.server.Tokens.admin;
import static io.kilnmark.server.Tokens.as;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.quarkus.security.identity.SecurityIdentity;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.restassured.path.json.JsonPath;

/**
 * Seeing unmasked values is a grant of its own (ADR 0027): only designer tokens hold it, on unless
 * created without it; asking for it on any other role's token is refused, and no role includes it.
 */
@QuarkusTest
class UnmaskGrantTest {

    private static JsonPath create(String role, Boolean unmask, int status) {
        Map<String, Object> body = new HashMap<>(Map.of("name", "grant " + role, "role", role));
        if (unmask != null) {
            body.put("unmask", unmask);
        }
        return admin().contentType(ContentType.JSON).body(body).post("/api/v1/tokens").then().statusCode(status)
            .extract().jsonPath();
    }

    @Test
    void onlyDesignerTokensHoldTheGrant() {
        JsonPath designer = create("designer", null, 201);
        assertTrue(designer.getBoolean("unmask"), "a designer token holds it by default");
        as(designer.getString("token")).get("/api/v1/me").then().statusCode(200)
            .body("role", equalTo("designer")).body("unmask", equalTo(true));

        JsonPath without = create("designer", false, 201);
        assertFalse(without.getBoolean("unmask"));
        as(without.getString("token")).get("/api/v1/me").then().statusCode(200).body("unmask", equalTo(false));

        for (String role : new String[] {"viewer", "steward", "admin"}) {
            assertFalse(create(role, null, 201).getBoolean("unmask"), role + " tokens do not hold it");
            assertFalse(create(role, false, 201).getBoolean("unmask"));
            admin().contentType(ContentType.JSON).body(Map.of("name", "grant " + role, "role", role, "unmask", true))
                .post("/api/v1/tokens").then().statusCode(400)
                .body("error", containsString("only a designer token can see unmasked values"));
        }
        admin().get("/api/v1/me").then().statusCode(200).body("role", equalTo("admin")).body("unmask", equalTo(false));

        admin().get("/api/v1/tokens").then().statusCode(200)
            .body("find { it.id == " + designer.getLong("id") + " }.unmask", equalTo(true))
            .body("find { it.id == " + without.getLong("id") + " }.unmask", equalTo(false));
    }

    @Test
    void noRoleIncludesTheGrant() {
        for (AccessRole r : AccessRole.values()) {
            assertFalse(r.grants().contains(AccessRole.UNMASK_GRANT), r.id());
            assertTrue(r.mayHoldUnmask() == (r == AccessRole.DESIGNER), r.id());
        }
    }

    @Test
    void theIdentityAddsItOnlyForADesigner() {
        SecurityIdentity designer = ApiTokenIdentityProvider.identity(
            new ApiTokens.Caller(1, "d", AccessRole.DESIGNER, null, true));
        assertTrue(designer.hasRole(AccessRole.UNMASK_GRANT));
        SecurityIdentity off = ApiTokenIdentityProvider.identity(
            new ApiTokens.Caller(2, "d2", AccessRole.DESIGNER, null, false));
        assertFalse(off.hasRole(AccessRole.UNMASK_GRANT));
        // Whatever was stored, an admin's identity never gets it.
        SecurityIdentity admin = ApiTokenIdentityProvider.identity(
            new ApiTokens.Caller(3, "a", AccessRole.ADMIN, null, true));
        assertTrue(admin.hasRole(AccessRole.DESIGNER_ROLE), "admins still include the designer role");
        assertFalse(admin.hasRole(AccessRole.UNMASK_GRANT), "but not the grant");
    }
}

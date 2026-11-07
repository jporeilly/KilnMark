package io.kilnmark.server.starter;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;

import org.junit.jupiter.api.Test;

/**
 * The deployment mode (ADR 0051): only production or starter-pack, in ASCII lower case; and a
 * setting that differs from the recorded mode stops the server, either way round, saying what to do.
 */
class ModeCheckTest {

    @Test
    void onlyProductionOrStarterPack() {
        assertNull(ModeCheck.problem("production"));
        assertNull(ModeCheck.problem("starter-pack"));
        for (String refused : Arrays.asList(null, "", "Production", "PRODUCTION", "starter_pack", "Starter-Pack",
            "starterpack", "true", "false", " production", "production ", "starter-pack\n", "production​")) {
            assertNotNull(ModeCheck.problem(refused), "[" + refused + "]");
        }
    }

    @Test
    void aSettingThatDiffersFromTheRecordStopsTheServerEitherWayRound() {
        assertNull(ModeCheck.mismatch("production", "production"));
        assertNull(ModeCheck.mismatch("starter-pack", "starter-pack"));
        String toProduction = ModeCheck.mismatch("production", "starter-pack");
        assertTrue(toProduction != null && toProduction.contains("never becomes a production one")
            && toProduction.contains("Install Kilnmark afresh"), toProduction);
        String toStarter = ModeCheck.mismatch("starter-pack", "production");
        assertTrue(toStarter != null && toStarter.contains("never comes to a production install"), toStarter);
        assertNotNull(ModeCheck.mismatch("production", "something else"));
    }

    @Test
    void aStarterPackInstallSignsInWithTheBundledKeycloakOnly() {
        String bundled = "http:" + "/" + "/keycloak:8080/realms/kilnmark";
        assertNull(ModeCheck.signInProblem("starter-pack", true, bundled));
        assertNull(ModeCheck.signInProblem("starter-pack", true, bundled + "/"));
        // sign-in off: refused, saying why
        String off = ModeCheck.signInProblem("starter-pack", false, bundled);
        assertTrue(off != null && off.contains("needs sign-in with the bundled Keycloak"), off);
        assertNotNull(ModeCheck.signInProblem("starter-pack", false, null));
        // an outside identity provider, or another realm: refused, never echoing the address
        for (String outside : Arrays.asList("https:" + "/" + "/login.bank.example/realms/kilnmark",
            "http:" + "/" + "/keycloak:8080/realms/other", "http:" + "/" + "/keycloak:8080/realms/kilnmark-x",
            "http:" + "/" + "/keycloak:9090/realms/kilnmark", "", null)) {
            String refused = ModeCheck.signInProblem("starter-pack", true, outside);
            assertTrue(refused != null && refused.contains("never an outside identity provider"), outside + ": " + refused);
            assertTrue(outside == null || outside.isEmpty() || !refused.contains(outside), "never echoes the address");
        }
        // production signs in however the site chose: the bundled Keycloak, its own provider, or tokens only
        assertNull(ModeCheck.signInProblem("production", false, null));
        assertNull(ModeCheck.signInProblem("production", true, "https:" + "/" + "/login.bank.example/realms/staff"));
        assertNull(ModeCheck.signInProblem("production", true, bundled));
    }
}

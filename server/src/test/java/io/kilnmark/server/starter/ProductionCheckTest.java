package io.kilnmark.server.starter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import io.kilnmark.server.auth.AccessRole;
import io.kilnmark.server.auth.ApiTokens;

/**
 * KILNMARK_PRODUCTION means one thing everywhere (ADR 0051): the server starts only with true or
 * false, in any case, which the control scripts and keycloak-setup read so too; and on production
 * the tokens the example accounts left are found however many tokens down the line.
 */
class ProductionCheckTest {

    @Test
    void onlyTrueOrFalseInAnyCase() {
        for (String ok : new String[] {null, "true", "TRUE", "True", "false", "FALSE", "False"}) {
            assertNull(ProductionCheck.problem(ok), ok);
        }
        // values the configuration alone would take as true, or as false: the server refuses them
        for (String refused : List.of("1", "0", "yes", "no", "on", "off", "Y", "", " true", "true ",
            "fal\u017Fe", "tru\u0435")) {   // a long s, a Cyrillic e: not false, not true
            String problem = ProductionCheck.problem(refused);
            assertNotNull(problem, "[" + refused + "]");
            assertEquals(-1, problem.indexOf("[" + refused + "]"), "never echoes the value");
        }
    }

    private static ApiTokens.Token token(long id, String createdBy, String approvedBy) {
        return new ApiTokens.Token(id, "t" + id, AccessRole.ADMIN, approvedBy != null, createdBy, Instant.EPOCH, null, null,
            null, null, null, approvedBy);
    }

    @Test
    void theExampleAccountsTokensAndAllTheyLedTo() {
        List<ApiTokens.Token> all = List.of(
            token(9, "token 8 (t8)", null),                                // made by 8, listed first
            token(8, "token 7 (t7)", null),
            token(7, "sso:alex.morgan@kilnmark.com (a1)", null),            // an example admin's
            token(6, "sso:Rita (r1)", "sso:alex.morgan@kilnmark.com (a1)"), // its grant approved by one
            token(5, "sso:Rita (r1)", "sso:Sam (s1)"),
            token(4, "token 5 (t5)", null),
            token(3, "token 70 (sso:alex.morgan@kilnmark.com)", null),     // a token named like one
            token(2, "server (environment)", null),
            token(1, null, null));
        assertEquals(Set.of(6L, 7L, 8L, 9L), ProductionCheck.tainted(all));
    }
}

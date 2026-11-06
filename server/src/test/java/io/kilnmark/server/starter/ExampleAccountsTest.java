package io.kilnmark.server.starter;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** How the access review and the Starter Pack page tell the example accounts' sign-ins (ADR 0051). */
class ExampleAccountsTest {

    @Test
    void anExampleAccountIsKnownByItsAddressAlone() {
        assertTrue(ExampleAccounts.isExample("sso:alex.morgan@kilnmark.com (3f2a0000-1111)"));
        assertTrue(ExampleAccounts.isExample("sso:Priya.Shah@Kilnmark.com"));
        assertFalse(ExampleAccounts.isExample("sso:alex.morgan@example.com (3f2a0000-1111)"));
        assertFalse(ExampleAccounts.isExample("sso:kilnmark.com.evil@example.com"));
        assertFalse(ExampleAccounts.isExample("sso:someone (alex@kilnmark.com)"), "the provider's ID does not count");
        assertFalse(ExampleAccounts.isExample(null));
    }
}

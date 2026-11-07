package io.kilnmark.server.starter;

import java.util.Locale;

/**
 * The Starter Pack's example accounts (ADR 0051): five people at kilnmark.com addresses, which the
 * control script and keycloak-setup make and remove in the bundled Keycloak. Kilnmark never makes or
 * changes them and holds no Keycloak credential; it only tells their sign-ins apart, so the access
 * review and Settings, Starter Pack can show them.
 */
public final class ExampleAccounts {

    private ExampleAccounts() {
    }

    /**
     * The Starter Pack's five example people, by their exact addresses (and user names), as
     * keycloak-setup makes them (containers/keycloak/setup.sh).
     */
    public static final java.util.List<String> PEOPLE = java.util.List.of("vera.lane@kilnmark.com",
        "sam.okafor@kilnmark.com", "dana.reyes@kilnmark.com", "priya.shah@kilnmark.com", "alex.morgan@kilnmark.com");

    /**
     * Whether a sign-in name is a kilnmark.com example account: as the audit log records it,
     * {@code sso:<user name>} and the provider's ID for them in brackets.
     */
    public static boolean isExample(String name) {
        if (name == null) {
            return false;
        }
        String n = name.toLowerCase(Locale.ROOT).strip();
        if (n.startsWith("sso:")) {
            n = n.substring(4);
        }
        int space = n.indexOf(' ');
        if (space >= 0) {
            n = n.substring(0, space); // the provider's ID, " (...)"
        }
        return n.endsWith("@kilnmark.com");
    }
}

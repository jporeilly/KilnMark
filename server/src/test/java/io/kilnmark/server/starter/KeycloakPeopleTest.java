package io.kilnmark.server.starter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;

import org.junit.jupiter.api.Test;

/** What the Starter Pack's people code decides without Keycloak (ADR 0051). */
class KeycloakPeopleTest {

    @Test
    void theAccessReviewTellsExampleAccountsByTheirAddress() {
        assertTrue(KeycloakPeople.isExample("sso:alex.morgan@kilnmark.com (3f2a0000-1111)"));
        assertTrue(KeycloakPeople.isExample("sso:Priya.Shah@Kilnmark.com"));
        assertFalse(KeycloakPeople.isExample("sso:alex.morgan@example.com (3f2a0000-1111)"));
        assertFalse(KeycloakPeople.isExample("sso:kilnmark.com.evil@example.com"));
        assertFalse(KeycloakPeople.isExample("sso:someone (alex@kilnmark.com)"), "the provider's ID does not count");
        assertFalse(KeycloakPeople.isExample(null));
    }

    @Test
    void theRealmIsTheIssuers() {
        assertEquals("kilnmark", KeycloakPeople.realmOf(URI.create("http:" + "/" + "/keycloak:8080/realms/kilnmark")));
        assertEquals("kilnmark", KeycloakPeople.realmOf(URI.create("https:" + "/" + "/id.example.com/realms/kilnmark/")));
        assertEquals(null, KeycloakPeople.realmOf(URI.create("https:" + "/" + "/login.example.com/tenant/v2.0")));
        assertEquals(null, KeycloakPeople.realmOf(URI.create("https:" + "/" + "/id.example.com/realms/kilnmark/x")));
    }

    @Test
    void theFiveAreOnePerRoleAndThePasswordIsLongAndNew() {
        assertEquals(5, KeycloakPeople.PEOPLE.size());
        assertEquals(1, KeycloakPeople.PEOPLE.stream().filter(KeycloakPeople.Person::unmask).count());
        String a = KeycloakPeople.newPassword();
        assertEquals(24, a.length());
        assertFalse(a.equals(KeycloakPeople.newPassword()));
    }
}

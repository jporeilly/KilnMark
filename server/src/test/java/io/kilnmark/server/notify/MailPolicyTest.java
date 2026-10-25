package io.kilnmark.server.notify;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * The rules that keep the mail password where it was entered for, and email where it may go:
 * password and destination, unencrypted sign-in, the configured password, locked settings, allowed
 * domains, and scrubbing a server's words.
 */
class MailPolicyTest {

    static final MailSettings CURRENT = Fixtures.settings("smtp.example.com", MailSecurity.STARTTLS, "kilnmark", null);

    static MailSettings.Valid next(MailSettings.Input in) {
        return MailSettings.validate(in, CURRENT);
    }

    static MailSettings.Input change(String host, String security, String username, String password) {
        return new MailSettings.Input(null, host, null, security, username, password, null, null, null, null, null, null,
            null, null, null, null);
    }

    @Test
    void aStoredPasswordGoesOnlyWhereItWasEnteredFor() {
        MailSettings.Valid sameAll = next(change(null, null, null, null));
        assertFalse(MailPolicy.destinationChanged(CURRENT, sameAll));
        assertEquals(MailPolicy.PasswordAction.KEEP, MailPolicy.password(true, false, sameAll));
        for (MailSettings.Input moved : List.of(change("attacker.example", null, null, null),
            change(null, "none", null, null), change(null, null, "someone-else", null),
            new MailSettings.Input(null, null, 2525, null, null, null, null, null, null, null, null, null, null, null,
                null, null))) {
            MailSettings.Valid v = next(moved);
            assertTrue(MailPolicy.destinationChanged(CURRENT, v), moved.toString());
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> MailPolicy.password(true, true, v), "keeping the password is refused: " + moved);
            assertTrue(e.getMessage().startsWith("password:"), e.getMessage());
        }
        MailSettings.Valid retyped = next(change("attacker.example", null, null, "new-secret"));
        assertEquals(MailPolicy.PasswordAction.REPLACE, MailPolicy.password(true, true, retyped));
        MailSettings.Valid noUser = next(change("other.example", null, "", null));
        assertEquals(MailPolicy.PasswordAction.REMOVE, MailPolicy.password(true, true, noUser),
            "no one signs in any more: the stored password goes");
        assertEquals(MailPolicy.PasswordAction.REMOVE, MailPolicy.password(true, false, next(change(null, null, null, ""))));
        assertFalse(MailPolicy.destinationChanged(CURRENT, next(change("SMTP.Example.com", null, null, null))),
            "host names are compared ignoring case");
        assertFalse(MailPolicy.destinationChanged(CURRENT, new MailSettings.Valid(true, "smtp.example.com", 587,
            MailSecurity.STARTTLS, "kilnmark", null, "k@example.com", null, null, false, List.of(), true, 5, 20, 24,
            "digest")), "port 587 is STARTTLS's usual port");
    }

    @Test
    void signingInWithoutEncryptionNeedsAnAdminsWordAndAPasswordEnteredInTheUi() {
        assertThrows(IllegalArgumentException.class,
            () -> MailPolicy.checkPlainAuth(MailSecurity.NONE, "kilnmark", false, true), "not allowed");
        assertDoesNotThrow(() -> MailPolicy.checkPlainAuth(MailSecurity.NONE, "kilnmark", true, true));
        IllegalArgumentException env = assertThrows(IllegalArgumentException.class,
            () -> MailPolicy.checkPlainAuth(MailSecurity.NONE, "kilnmark", true, false));
        assertTrue(env.getMessage().contains("never sent unencrypted"), "the configured password: " + env.getMessage());
        assertDoesNotThrow(() -> MailPolicy.checkPlainAuth(MailSecurity.NONE, null, false, false), "no sign-in");
        assertDoesNotThrow(() -> MailPolicy.checkPlainAuth(MailSecurity.STARTTLS, "kilnmark", false, false));
        assertDoesNotThrow(() -> MailPolicy.checkPlainAuth(MailSecurity.TLS, "kilnmark", false, false));
    }

    @Test
    void theConfiguredPasswordGoesOnlyToTheConfiguredServerAndUser() {
        assertTrue(MailPolicy.configuredPasswordApplies("smtp.example.com", "kilnmark", "smtp.example.com", "kilnmark"));
        assertTrue(MailPolicy.configuredPasswordApplies("SMTP.example.com ", "kilnmark", "smtp.example.com", "kilnmark"));
        assertFalse(MailPolicy.configuredPasswordApplies("smtp.example.com", "kilnmark", "attacker.example", "kilnmark"));
        assertFalse(MailPolicy.configuredPasswordApplies("smtp.example.com", "kilnmark", "smtp.example.com", "other"));
        assertFalse(MailPolicy.configuredPasswordApplies(null, "kilnmark", "smtp.example.com", "kilnmark"),
            "no configured server: never");
    }

    @Test
    void lockedSettingsRefuseAChange() {
        MailSettings locked = Fixtures.locked(CURRENT, "allow-plain-auth", "allowed-domains", "host");
        MailSettings.Input same = Fixtures.input();
        assertDoesNotThrow(() -> MailPolicy.checkLocked(locked, MailSettings.validate(same, locked)), "unchanged");
        MailSettings.Input plain = new MailSettings.Input(null, null, null, null, null, null, null, null, null, true, null,
            null, null, null, null, null);
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
            () -> MailPolicy.checkLocked(locked, MailSettings.validate(plain, locked)));
        assertEquals("allow-plain-auth is set by the server's configuration (kilnmark.mail.locked) and cannot be changed "
            + "here", e.getMessage());
        MailSettings.Input both = new MailSettings.Input(null, "other.example", null, null, null, null, null, null, null,
            null, List.of("example.com"), null, null, null, null, null);
        IllegalArgumentException two = assertThrows(IllegalArgumentException.class,
            () -> MailPolicy.checkLocked(locked, MailSettings.validate(both, locked)));
        assertTrue(two.getMessage().contains("host") && two.getMessage().contains("allowed-domains"), two.getMessage());
        MailSettings.Input free = new MailSettings.Input(null, null, null, null, null, null, null, null, null, null, null,
            null, 3, null, null, null);
        assertDoesNotThrow(() -> MailPolicy.checkLocked(locked, MailSettings.validate(free, locked)), "not locked");
        assertEquals(java.util.Set.of("allowed-domains", "max-attempts"),
            MailPolicy.locked(List.of(" Allowed-Domains", "max-attempts", "")));
        assertThrows(IllegalStateException.class, () -> MailPolicy.locked(List.of("passwrod")), "a typo stops the server");
    }

    @Test
    void emailGoesOnlyToTheAllowedDomains() {
        List<String> domains = List.of("example.com", "example.org");
        assertTrue(MailPolicy.domainAllowed("a@example.com", domains));
        assertTrue(MailPolicy.domainAllowed("a@mail.example.com", domains), "subdomains too");
        assertTrue(MailPolicy.domainAllowed("a@EXAMPLE.ORG", domains));
        assertFalse(MailPolicy.domainAllowed("a@badexample.com", domains), "not a suffix match");
        assertFalse(MailPolicy.domainAllowed("a@example.net", domains));
        assertTrue(MailPolicy.domainAllowed("a@anything.test", List.of()), "none listed: any");
        assertThrows(IllegalArgumentException.class, () -> MailPolicy.checkDomain("email", "a@example.net", domains));
        assertEquals(List.of("example.com", "mail.example.org"),
            MailSettings.domains(List.of(" Example.com", "@mail.example.org", "", "example.com")));
        assertThrows(IllegalArgumentException.class, () -> MailSettings.domains(List.of("not a domain")));
        assertThrows(IllegalArgumentException.class, () -> MailSettings.domains(List.of("localhost")));
    }

    @Test
    void numbersStayWithinTheirBounds() {
        for (MailSettings.Input bad : List.of(
            new MailSettings.Input(null, null, null, null, null, null, null, null, null, null, null, null, 0, null, null, null),
            new MailSettings.Input(null, null, null, null, null, null, null, null, null, null, null, null, 11, null, null, null),
            new MailSettings.Input(null, null, null, null, null, null, null, null, null, null, null, null, null, 0, null, null),
            new MailSettings.Input(null, null, null, null, null, null, null, null, null, null, null, null, null, 1001, null, null),
            new MailSettings.Input(null, null, null, null, null, null, null, null, null, null, null, null, null, null, 169, null),
            new MailSettings.Input(null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, "bin"))) {
            assertThrows(IllegalArgumentException.class, () -> next(bad), bad.toString());
        }
    }

    @Test
    void aServersWordsAreScrubbedOfTheSignIn() {
        String user = "kilnmark";
        String pw = "S3cret-Pa55";
        String b64pw = Base64.getEncoder().encodeToString(pw.getBytes(StandardCharsets.UTF_8));
        String plain = Base64.getEncoder().encodeToString(("\0" + user + "\0" + pw).getBytes(StandardCharsets.UTF_8));
        String b64user = Base64.getEncoder().encodeToString(user.getBytes(StandardCharsets.UTF_8));
        String said = "535 5.7.8 invalid response " + b64pw + " / " + plain + " / " + pw + " for " + b64user + " "
            + b64pw.replace("=", "");
        String clean = MailPolicy.scrub(said, user, pw);
        for (String secret : List.of(pw, b64pw, plain, b64user)) {
            assertFalse(clean.contains(secret), secret + " in " + clean);
        }
        assertTrue(clean.startsWith("535 5.7.8 invalid response [hidden]"), clean);
        assertEquals("nothing to hide", MailPolicy.scrub("nothing to hide", null, null));
        assertEquals("a b", MailPolicy.scrub("a b", "a", null), "a short user name is not blanked out of every word");
    }
}

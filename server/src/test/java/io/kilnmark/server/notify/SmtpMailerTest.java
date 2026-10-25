package io.kilnmark.server.notify;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.ServerSocket;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.icegreen.greenmail.configuration.GreenMailConfiguration;
import com.icegreen.greenmail.util.GreenMail;
import com.icegreen.greenmail.util.ServerSetup;

import io.kilnmark.server.auth.AccessRole;
import jakarta.mail.Multipart;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;

/**
 * Sending through a real SMTP conversation with GreenMail, an SMTP server in this test's own
 * process (no container): the message's parts and headers, and failures in plain words without the
 * password.
 */
class SmtpMailerTest {

    static final String PASSWORD = "pw-never-shown-5521";
    GreenMail smtp;

    @BeforeEach
    void start() {
        smtp = new GreenMail(new ServerSetup(0, "127.0.0.1", ServerSetup.PROTOCOL_SMTP));
        smtp.withConfiguration(GreenMailConfiguration.aConfig().withUser("kilnmark", PASSWORD));
        smtp.start();
    }

    @AfterEach
    void stop() {
        smtp.stop();
    }

    SmtpMailer.Server server(String user, String password, MailSecurity security) {
        return new SmtpMailer.Server("127.0.0.1", smtp.getSmtp().getPort(), security, user, password,
            "kilnmark@example.com", "Kilnmark", Duration.ofSeconds(5));
    }

    static MailMessages.Mail mail() {
        MailSettings settings = new MailSettings(true, "127.0.0.1", null, MailSecurity.NONE, null, false,
            "kilnmark@example.com", "Kilnmark", "https://kilnmark.example.com", true, null, null);
        NotificationEvent e = new NotificationEvent(NotificationKind.RULE_FAILED, Severity.HIGH,
            "urn:kilnmark:lab:crm.customers", "email not null fails on crm.customers — für alle",
            "312 of 48,210 rows (0.65%) fail.", NotificationEvent.assetLink("urn:kilnmark:lab:crm.customers"), "lab",
            "email", null, "rule:1", Instant.parse("2026-10-25T06:00:00Z"));
        return new MailMessages(settings, ZoneOffset.UTC, u -> Set.of()).single(e,
            new MailMessages.Recipient("steward@example.com", AccessRole.STEWARD, false));
    }

    @Test
    void sendsPlainTextAndHtmlMarkedAsAutomatic() throws Exception {
        new SmtpMailer().send(server("kilnmark", PASSWORD, MailSecurity.NONE), "steward@example.com", mail());
        assertTrue(smtp.waitForIncomingEmail(5000, 1));
        MimeMessage m = smtp.getReceivedMessages()[0];
        assertEquals("[Kilnmark] High: email not null fails on crm.customers — für alle", m.getSubject());
        assertEquals("auto-generated", m.getHeader("Auto-Submitted")[0]);
        InternetAddress from = (InternetAddress) m.getFrom()[0];
        assertEquals("kilnmark@example.com", from.getAddress());
        assertEquals("Kilnmark", from.getPersonal());
        assertEquals("steward@example.com", m.getAllRecipients()[0].toString());
        Multipart parts = (Multipart) m.getContent();
        assertTrue(m.getContentType().startsWith("multipart/alternative"), m.getContentType());
        assertEquals(2, parts.getCount());
        assertTrue(parts.getBodyPart(0).isMimeType("text/plain"));
        assertTrue(parts.getBodyPart(1).isMimeType("text/html"));
        assertTrue(((String) parts.getBodyPart(0).getContent()).contains("312 of 48,210 rows (0.65%) fail."));
        assertFalse(((String) parts.getBodyPart(1).getContent()).contains("<img"));
    }

    @Test
    void sendsWithoutSigningInWhenNoUserIsGiven() throws Exception {
        new SmtpMailer().send(server(null, null, MailSecurity.NONE), "team@example.com", mail());
        assertTrue(smtp.waitForIncomingEmail(5000, 1));
    }

    @Test
    void aWrongPasswordIsExplainedWithoutRepeatingIt() {
        SmtpMailer.Failure f = assertThrows(SmtpMailer.Failure.class, () -> new SmtpMailer().send(
            server("kilnmark", "wrong-" + PASSWORD, MailSecurity.NONE), "steward@example.com", mail()));
        assertTrue(f.getMessage().startsWith("The mail server refused the user name or password"), f.getMessage());
        assertFalse(f.getMessage().contains(PASSWORD), f.getMessage());
        assertFalse(f.permanent(), "fixable: worth trying again after the admin corrects it");
    }

    @Test
    void aServerWithoutStarttlsIsExplained() {
        SmtpMailer.Failure f = assertThrows(SmtpMailer.Failure.class, () -> new SmtpMailer().send(
            server("kilnmark", PASSWORD, MailSecurity.STARTTLS), "steward@example.com", mail()));
        assertTrue(f.getMessage().contains("STARTTLS"), f.getMessage());
    }

    @Test
    void nothingListeningIsExplained() throws IOException {
        int closed;
        try (ServerSocket s = new ServerSocket(0)) {
            closed = s.getLocalPort();
        }
        SmtpMailer.Server s = new SmtpMailer.Server("127.0.0.1", closed, MailSecurity.NONE, null, null,
            "kilnmark@example.com", null, Duration.ofSeconds(3));
        SmtpMailer.Failure f = assertThrows(SmtpMailer.Failure.class, () -> new SmtpMailer().send(s,
            "steward@example.com", mail()));
        assertTrue(f.getMessage().startsWith("Nothing accepted a connection at 127.0.0.1 port " + closed), f.getMessage());
    }

    @Test
    void anUnknownHostIsExplained() {
        SmtpMailer.Server s = new SmtpMailer.Server("no-such-host.invalid", 25, MailSecurity.NONE, null, null,
            "kilnmark@example.com", null, Duration.ofSeconds(3));
        SmtpMailer.Failure f = assertThrows(SmtpMailer.Failure.class, () -> new SmtpMailer().send(s,
            "steward@example.com", mail()));
        assertTrue(f.getMessage().contains("no-such-host.invalid could not be found"), f.getMessage());
    }

    @Test
    void theServerNeverPrintsItsPassword() {
        SmtpMailer.Server s = server("kilnmark", PASSWORD, MailSecurity.TLS);
        assertFalse(s.toString().contains(PASSWORD), s.toString());
        assertEquals("false", SmtpMailer.properties(s).getProperty("mail.debug"), "tracing would log the sign-in");
        assertEquals("true", SmtpMailer.properties(s).getProperty("mail.smtps.ssl.checkserveridentity"));
        assertFalse(SmtpMailer.properties(s).containsValue(PASSWORD));
    }
}

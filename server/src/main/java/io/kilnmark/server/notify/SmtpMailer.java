package io.kilnmark.server.notify;

import java.io.UnsupportedEncodingException;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Date;
import java.util.Locale;
import java.util.Properties;

import javax.net.ssl.SSLException;

import org.eclipse.angus.mail.smtp.SMTPAddressFailedException;
import org.eclipse.angus.mail.smtp.SMTPSendFailedException;
import org.eclipse.angus.mail.smtp.SMTPSenderFailedException;

import jakarta.mail.AuthenticationFailedException;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.Session;
import jakarta.mail.Transport;
import jakarta.mail.internet.AddressException;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeBodyPart;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;

/**
 * Sends one email over SMTP with Jakarta Mail, with the settings it is given (they live in the
 * database and change at run time, which is why this is not the Quarkus mailer). Each email is
 * plain text and HTML ({@code multipart/alternative}), marked as sent automatically so that
 * out-of-office replies stay away. Failures come back in plain words, never with the password,
 * and say whether trying again could help; any text from the server is scrubbed of the user name
 * and password in every form it could echo ({@link MailPolicy#scrub}). Protocol tracing
 * ({@code mail.debug}) is always off: it would write the sign-in to the log. Only the PLAIN and
 * LOGIN mechanisms are offered (never NTLM or XOAUTH2), and a password never goes unencrypted
 * unless {@code allowPlainAuth} says an admin allowed it for a password they entered.
 */
public final class SmtpMailer {

    /**
     * Where and how to send. {@link #toString} leaves the password out, so a server never reaches a
     * log with it. {@code allowPlainAuth}: an admin allowed this password to go without encryption.
     */
    public record Server(String host, int port, MailSecurity security, String username, String password,
                         String fromAddress, String fromName, Duration timeout, boolean allowPlainAuth) {
        @Override
        public String toString() {
            return "Server[" + host + ":" + port + ", " + security.id() + (username == null ? "" : ", as " + username)
                + "]";
        }
    }

    /** Sending failed: {@code getMessage()} says why in plain words; {@code permanent} if trying again cannot help. */
    public static final class Failure extends Exception {
        @java.io.Serial
        private static final long serialVersionUID = 1L;
        private final boolean permanent;

        Failure(String message, boolean permanent, Throwable cause) {
            super(message, cause);
            this.permanent = permanent;
        }

        public boolean permanent() {
            return permanent;
        }
    }

    /** Sends {@code mail} to {@code to}. */
    public void send(Server s, String to, MailMessages.Mail mail) throws Failure {
        if (s.security() == MailSecurity.NONE && s.username() != null && !s.allowPlainAuth()) {
            throw new Failure("Not sent: with security None the password would go unencrypted. Choose STARTTLS or TLS "
                + "under Settings, Email, or allow sign-in without encryption there (only for a password entered "
                + "there).", false, null);
        }
        Session session = Session.getInstance(properties(s));
        try {
            MimeMessage m = new MimeMessage(session);
            m.setFrom(s.fromName() == null ? new InternetAddress(s.fromAddress(), true)
                : new InternetAddress(s.fromAddress(), s.fromName(), StandardCharsets.UTF_8.name()));
            m.setRecipient(Message.RecipientType.TO, new InternetAddress(to, true));
            m.setSubject(mail.subject(), StandardCharsets.UTF_8.name());
            m.setSentDate(new Date());
            m.setHeader("Auto-Submitted", "auto-generated");
            m.setHeader("X-Auto-Response-Suppress", "All");
            MimeBodyPart text = new MimeBodyPart();
            text.setText(mail.text(), StandardCharsets.UTF_8.name(), "plain");
            MimeBodyPart html = new MimeBodyPart();
            html.setText(mail.html(), StandardCharsets.UTF_8.name(), "html");
            MimeMultipart body = new MimeMultipart("alternative");
            body.addBodyPart(text);
            body.addBodyPart(html);
            m.setContent(body);
            m.saveChanges();
            try (Transport t = session.getTransport(protocol(s))) {
                t.connect(s.host(), s.port(), s.username(), s.username() == null ? null : s.password());
                t.sendMessage(m, m.getAllRecipients());
            }
        } catch (AddressException e) {
            throw new Failure(MailPolicy.scrub("The address '" + clean(e.getRef()) + "' is not one a mail server "
                + "accepts.", s.username(), s.password()), true, null);
        } catch (MessagingException | UnsupportedEncodingException e) {
            throw new Failure(MailPolicy.scrub(explain(e, s), s.username(), s.password()), permanent(e), null);
        }
    }

    private static String protocol(Server s) {
        return s.security() == MailSecurity.TLS ? "smtps" : "smtp";
    }

    static Properties properties(Server s) {
        String p = "mail." + protocol(s);
        String millis = Long.toString(Math.max(1000, s.timeout().toMillis()));
        Properties props = new Properties();
        props.put("mail.debug", "false");
        props.put("mail.transport.protocol", protocol(s));
        props.put(p + ".host", s.host());
        props.put(p + ".port", Integer.toString(s.port()));
        props.put(p + ".auth", Boolean.toString(s.username() != null));
        props.put(p + ".auth.mechanisms", "PLAIN LOGIN");
        props.put(p + ".auth.ntlm.disable", "true");
        props.put(p + ".auth.xoauth2.disable", "true");
        props.put(p + ".connectiontimeout", millis);
        props.put(p + ".timeout", millis);
        props.put(p + ".writetimeout", millis);
        props.put(p + ".from", s.fromAddress());
        if (s.security() == MailSecurity.STARTTLS) {
            props.put(p + ".starttls.enable", "true");
            props.put(p + ".starttls.required", "true");
        }
        if (s.security() != MailSecurity.NONE) {
            props.put(p + ".ssl.checkserveridentity", "true");
        }
        return props;
    }

    /** Whether trying again cannot help: the server refused the sender, recipient or message outright (5xx). */
    static boolean permanent(Exception e) {
        // A MessagingException's cause is its "next exception": the chain holds the server's answers.
        for (Throwable c = e; c != null; c = c.getCause()) {
            if (c instanceof SMTPSendFailedException f) {
                return f.getReturnCode() >= 500;
            }
            if (c instanceof SMTPAddressFailedException f) {
                return f.getReturnCode() >= 500;
            }
            if (c instanceof SMTPSenderFailedException f) {
                return f.getReturnCode() >= 500;
            }
        }
        return false;
    }

    /** Why sending failed, in plain words, with what to check. */
    static String explain(Exception e, Server s) {
        String where = s.host() + " port " + s.port();
        long seconds = Math.max(1, s.timeout().toSeconds());
        for (Throwable c = e; c != null; c = c.getCause()) {
            if (c instanceof AuthenticationFailedException) {
                return "The mail server refused the user name or password" + said(c) + ". Check them; some services "
                    + "need an app password, or signing in allowed for this account.";
            }
            if (c instanceof UnknownHostException) {
                return "The mail server " + s.host() + " could not be found. Check its name, and that this server can look "
                    + "up names on its network.";
            }
            if (c instanceof ConnectException || c instanceof NoRouteToHostException) {
                return "Nothing accepted a connection at " + where + ". Check the host and port, and that a firewall lets "
                    + "this server reach it.";
            }
            if (c instanceof SocketTimeoutException) {
                return "The mail server at " + where + " did not answer within " + seconds + " seconds. Check the port and "
                    + "the security setting: port 465 usually takes TLS, port 587 STARTTLS.";
            }
            if (c instanceof SMTPAddressFailedException f) {
                return "The mail server refused the recipient " + clean(String.valueOf(f.getAddress())) + ": "
                    + clean(f.getMessage()) + (f.getReturnCode() >= 500 ? "" : " It may accept it later.");
            }
            if (c instanceof SMTPSenderFailedException f) {
                return "The mail server refused the sender address " + s.fromAddress() + ": " + clean(f.getMessage())
                    + ". Use an address this account may send from.";
            }
            if (c instanceof SMTPSendFailedException f) {
                return "The mail server refused the email: " + clean(f.getMessage());
            }
            if (c instanceof SSLException) {
                String m = String.valueOf(c.getMessage());
                if (m.contains("PKIX") || m.contains("certification path")) {
                    return "The mail server's certificate is not trusted by this server's Java. Use a certificate from "
                        + "a public authority, or add the authority that issued it to the server's trust store.";
                }
                if (m.contains("No subject alternative") || m.contains("doesn't match")) {
                    return "The mail server's certificate is for another name than " + s.host() + ". Enter the name on "
                        + "its certificate as the host.";
                }
                if (m.contains("Unrecognized SSL message") || m.contains("plaintext")) {
                    return "The mail server at " + where + " did not answer with TLS. Choose STARTTLS (usually port 587) "
                        + "or None.";
                }
                return "The secure connection to " + where + " failed (" + clean(m) + "). Check the security setting: "
                    + "port 465 usually takes TLS, port 587 STARTTLS.";
            }
        }
        String m = String.valueOf(e.getMessage());
        if (m.toLowerCase(Locale.ROOT).contains("starttls")) {
            return "The mail server at " + where + " does not offer STARTTLS. Choose TLS if it listens for TLS (usually "
                + "port 465), or None for a relay on a trusted network.";
        }
        return "Sending failed: " + clean(m);
    }

    private static String said(Throwable c) {
        String m = c.getMessage();
        return m == null || m.isBlank() ? "" : " (it said: " + clean(m) + ")";
    }

    /** Server text for a message: one line, no control characters, at most 300 characters. */
    static String clean(String text) {
        if (text == null) {
            return "";
        }
        String s = text.replaceAll("[\\p{Cntrl}]+", " ").strip();
        return s.length() <= 300 ? s : s.substring(0, 299) + "…";
    }
}

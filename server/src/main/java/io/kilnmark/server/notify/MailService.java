package io.kilnmark.server.notify;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.jboss.logging.Logger;

import io.kilnmark.server.KilnmarkConfig;
import io.kilnmark.server.SecretBox;
import io.quarkus.runtime.ShutdownEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;

/**
 * The mail settings in force: an admin's saved settings, or until then the server's
 * {@code kilnmark.mail.*} configuration. Opens the stored password only to send, and never hands
 * it out. Test emails are sent on a thread of their own, never on the request's.
 */
@ApplicationScoped
public class MailService {

    private static final Logger LOG = Logger.getLogger(MailService.class);

    private final NotificationStore store;
    private final SecretBox secrets;
    private final KilnmarkConfig config;
    private final SmtpMailer mailer = new SmtpMailer();
    /** Test emails: a virtual thread each, so a slow mail server never holds a request thread. */
    private final ExecutorService tests = Executors.newVirtualThreadPerTaskExecutor();

    public MailService(NotificationStore store, SecretBox secrets, KilnmarkConfig config) {
        this.store = store;
        this.secrets = secrets;
        this.config = config;
    }

    void onStop(@Observes ShutdownEvent event) {
        tests.shutdownNow();
    }

    /** The settings in force, without the password. */
    public MailSettings settings() {
        return store.settings().map(s -> new MailSettings(s.enabled(), s.host(), s.port(), s.security(), s.username(),
                s.password() != null || configuredPassword() != null, s.fromAddress(), s.fromName(), s.baseUrl(), true,
                s.updatedBy(), s.updatedAt()))
            .orElseGet(this::configured);
    }

    /** The server's configuration, as settings (invalid values are left out, with a warning). */
    MailSettings configured() {
        KilnmarkConfig.Mail m = config.mail();
        String from = null;
        String base = null;
        try {
            from = m.from().map(Subscription::email).orElse(null);
            base = m.baseUrl().map(MailSettings::baseUrl).orElse(null);
        } catch (IllegalArgumentException e) {
            LOG.warnf("kilnmark.mail: %s", e.getMessage());
        }
        return new MailSettings(true, m.host().map(String::strip).filter(h -> !h.isEmpty()).orElse(null),
            m.port().orElse(null), m.security(), m.username().filter(u -> !u.isBlank()).orElse(null),
            configuredPassword() != null, from, m.fromName(), base, false, null, null);
    }

    private String configuredPassword() {
        return config.mail().password().filter(p -> !p.isEmpty()).orElse(null);
    }

    /** Saves an admin's settings; {@code password} null keeps the stored one, "" removes it. */
    public MailSettings save(MailSettings.Valid v, String by) {
        boolean keep = v.password() == null;
        byte[] sealed = keep || v.password().isEmpty() ? null : secrets.seal(v.password());
        store.saveSettings(v, sealed, keep, by);
        LOG.infof("Mail settings saved by %s (server %s, port %s, %s)", by.replaceAll("\\p{Cntrl}", "?"), v.host(),
            v.port() == null ? "default" : v.port(), v.security().id());
        return settings();
    }

    /** Where and how to send now, with the password opened; null when email is not set up. */
    SmtpMailer.Server server() {
        MailSettings s = settings();
        if (s.host() == null || s.fromAddress() == null) {
            return null;
        }
        String password = store.settings().map(NotificationStore.SavedSettings::password).map(secrets::open)
            .orElseGet(this::configuredPassword);
        return new SmtpMailer.Server(s.host(), s.effectivePort(), s.security(), s.username(), password,
            s.fromAddress(), s.fromName(), timeout());
    }

    Duration timeout() {
        return config.notifications().sendTimeout();
    }

    SmtpMailer mailer() {
        return mailer;
    }

    /** The answer to "Send a test email". */
    public record TestResult(boolean ok, String message) {
    }

    /**
     * Sends a test email to {@code to} with the settings in force (even while sending is paused), on
     * a thread of its own; the answer says what happened in plain words. Logged like any send.
     */
    public CompletionStage<TestResult> test(String to, String by) {
        String address = Subscription.email(to);
        if (address == null) {
            throw new IllegalArgumentException("to: enter the address to send the test email to");
        }
        SmtpMailer.Server server = server();
        if (server == null) {
            return CompletableFuture.completedFuture(new TestResult(false, "Enter the mail server and the sender's "
                + "address first, and save them."));
        }
        return CompletableFuture.supplyAsync(() -> {
            MailMessages.Mail mail = new MailMessages(settings(), java.time.ZoneId.systemDefault(), u -> java.util.Set.of())
                .test(by);
            try {
                mailer.send(server, address, mail);
                store.log(address, null, "test", java.util.List.of(), mail.subject(), "sent", 1, null);
                return new TestResult(true, "Sent to " + address + " through " + server.host() + " port "
                    + server.port() + ". Check that it arrived, and not in a spam folder.");
            } catch (SmtpMailer.Failure f) {
                store.log(address, null, "test", java.util.List.of(), mail.subject(), "failed", 1, f.getMessage());
                return new TestResult(false, f.getMessage());
            }
        }, tests);
    }
}

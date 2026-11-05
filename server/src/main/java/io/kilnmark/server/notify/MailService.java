package io.kilnmark.server.notify;

import java.time.Duration;
import java.time.ZoneId;
import java.util.List;
import java.util.Set;
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
 * {@code kilnmark.mail.*} configuration, with locked settings always from the configuration. Opens
 * the stored password only to send, never hands it out, and only sends it where {@link MailPolicy}
 * allows. Test and confirmation emails are sent on threads of their own, never on the request's.
 */
@ApplicationScoped
public class MailService {

    private static final Logger LOG = Logger.getLogger(MailService.class);

    private final NotificationStore store;
    private final SecretBox secrets;
    private final KilnmarkConfig config;
    private final Set<String> locked;
    private final SmtpMailer mailer = new SmtpMailer();
    /** Test and confirmation emails: a virtual thread each, so a slow mail server never holds a request thread. */
    private final ExecutorService sends = Executors.newVirtualThreadPerTaskExecutor();

    public MailService(NotificationStore store, SecretBox secrets, KilnmarkConfig config) {
        this.store = store;
        this.secrets = secrets;
        this.config = config;
        this.locked = MailPolicy.locked(config.mail().locked().orElse(List.of()));
    }

    void onStop(@Observes ShutdownEvent event) {
        sends.shutdownNow();
    }

    /** The settings in force, without the password. */
    public MailSettings settings() {
        MailSettings conf = configured();
        return store.settings().map(s -> merge(s, conf)).orElse(conf);
    }

    /** Saved settings, with each locked one taken from the configuration. */
    private MailSettings merge(NotificationStore.SavedSettings s, MailSettings conf) {
        String host = locked.contains("host") ? conf.host() : s.host();
        String username = locked.contains("username") ? conf.username() : s.username();
        boolean hasPassword = s.password() != null || configuredPasswordApplies(host, username);
        return new MailSettings(s.enabled(), host,
            locked.contains("port") ? conf.port() : s.port(),
            locked.contains("security") ? conf.security() : s.security(), username, hasPassword,
            locked.contains("from") ? conf.fromAddress() : s.fromAddress(),
            locked.contains("from-name") ? conf.fromName() : s.fromName(),
            locked.contains("base-url") ? conf.baseUrl() : s.baseUrl(),
            locked.contains("allow-plain-auth") ? conf.allowPlainAuth() : s.allowPlainAuth(),
            locked.contains("allowed-domains") ? conf.allowedDomains() : s.allowedDomains(),
            locked.contains("require-confirmation") ? conf.requireConfirmation() : s.requireConfirmation(),
            locked.contains("max-attempts") ? conf.maxAttempts() : s.maxAttempts(),
            locked.contains("rate-limit-per-hour") ? conf.rateLimitPerHour() : s.rateLimitPerHour(),
            locked.contains("undelivered-after") ? conf.undeliveredHours() : s.undeliveredHours(),
            locked.contains("undelivered-action") ? conf.undeliveredAction() : s.undeliveredAction(),
            locked, true, s.updatedBy(), s.updatedAt());
    }

    /** The server's configuration, as settings (invalid values are left out, with a warning). */
    MailSettings configured() {
        KilnmarkConfig.Mail m = config.mail();
        KilnmarkConfig.Notifications n = config.notifications();
        String from = null;
        String base = null;
        List<String> domains = List.of();
        try {
            from = m.from().map(Subscription::email).orElse(null);
            base = m.baseUrl().map(MailSettings::baseUrl).orElse(null);
            domains = MailSettings.domains(m.allowedDomains().orElse(List.of()));
        } catch (IllegalArgumentException e) {
            LOG.warnf("kilnmark.mail: %s", e.getMessage());
        }
        String host = m.host().map(String::strip).filter(h -> !h.isEmpty()).map(h -> h.toLowerCase(java.util.Locale.ROOT))
            .orElse(null);
        String username = m.username().filter(u -> !u.isBlank()).map(String::strip).orElse(null);
        String action = "drop".equalsIgnoreCase(n.undeliveredAction().strip()) ? "drop" : "digest";
        return new MailSettings(true, host, m.port().orElse(null), m.security(), username,
            configuredPasswordApplies(host, username), from, m.fromName(), base, m.allowPlainAuth(), domains,
            m.requireConfirmation(), clamp(n.maxAttempts(), MailSettings.MIN_ATTEMPTS, MailSettings.MAX_ATTEMPTS),
            clamp(n.rateLimitPerHour(), MailSettings.MIN_RATE, MailSettings.MAX_RATE),
            clamp((int) n.undeliveredAfter().toHours(), MailSettings.MIN_UNDELIVERED_HOURS,
                MailSettings.MAX_UNDELIVERED_HOURS), action, locked, false, null, null);
    }

    private static int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }

    private String configuredPassword() {
        return config.mail().password().filter(p -> !p.isEmpty()).orElse(null);
    }

    /** Whether the configured password would be sent to {@code host} as {@code username}. */
    private boolean configuredPasswordApplies(String host, String username) {
        return configuredPassword() != null && MailPolicy.configuredPasswordApplies(config.mail().host().orElse(null),
            config.mail().username().orElse(null), host, username);
    }

    /** What saving did: the settings now in force, and the addresses paused by the allowed domains. */
    public record Saved(MailSettings settings, List<NotificationStore.Subscriber> paused) {
    }

    /**
     * Saves an admin's settings: refuses locked settings, keeps the stored password only while it
     * would go where it was entered for, refuses unencrypted sign-in unless allowed, and pauses
     * addresses outside the allowed domains.
     */
    public Saved save(MailSettings.Input input, String by) {
        MailSettings current = settings();
        MailSettings.Valid v = MailSettings.validate(input, current);
        MailPolicy.checkLocked(current, v);
        boolean stored = store.settings().map(s -> s.password() != null).orElse(false);
        MailPolicy.PasswordAction action = MailPolicy.password(stored, MailPolicy.destinationChanged(current, v), v);
        boolean entered = action == MailPolicy.PasswordAction.REPLACE
            || (action == MailPolicy.PasswordAction.KEEP && stored);
        MailPolicy.checkPlainAuth(v.security(), v.username(), v.allowPlainAuth(), entered);
        byte[] sealed = action == MailPolicy.PasswordAction.REPLACE ? secrets.seal(v.password()) : null;
        store.saveSettings(v, action, sealed, by);
        String who = by.replaceAll("\\p{Cntrl}", "?");
        LOG.infof("Mail settings saved by %s (server %s, port %d, %s%s)", who, v.host(), v.effectivePort(),
            v.security().id(), switch (action) {
                case KEEP -> "";
                case REPLACE -> ", new password";
                case REMOVE -> ", password removed";
            });
        if (v.allowPlainAuth() && !current.allowPlainAuth()) {
            LOG.warnf("%s allowed signing in to the mail server without encryption (security none): the mail password "
                + "can then be read on the network", who);
        }
        if (v.requireConfirmation() != current.requireConfirmation()) {
            LOG.infof("%s turned confirmation of personal email addresses %s", who, v.requireConfirmation() ? "on" : "off");
        }
        List<NotificationStore.Subscriber> paused = store.pauseOutside(v.allowedDomains());
        return new Saved(settings(), paused);
    }

    /** Addresses paused for now (outside the allowed domains). */
    public List<NotificationStore.Subscriber> paused() {
        return store.paused();
    }

    /**
     * Where and how to send now, with the password opened; null when email is not set up. The stored
     * password goes where it was saved for; the configured one only to the configured server and user;
     * neither unencrypted unless {@link MailPolicy#checkPlainAuth} allows.
     */
    SmtpMailer.Server server() {
        MailSettings s = settings();
        if (s.host() == null || s.fromAddress() == null) {
            return null;
        }
        byte[] sealed = store.settings().map(NotificationStore.SavedSettings::password).orElse(null);
        String password = null;
        boolean entered = false;
        if (sealed != null) {
            password = secrets.open(sealed);
            entered = true;
        } else if (configuredPasswordApplies(s.host(), s.username())) {
            password = configuredPassword();
        }
        return new SmtpMailer.Server(s.host(), s.effectivePort(), s.security(), s.username(), password,
            s.fromAddress(), s.fromName(), timeout(), s.allowPlainAuth() && entered);
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
        MailSettings settings = settings();
        MailPolicy.checkDomain("to", address, settings.allowedDomains());
        SmtpMailer.Server server = server();
        if (server == null) {
            return CompletableFuture.completedFuture(new TestResult(false, "Enter the mail server and the sender's "
                + "address first, and save them."));
        }
        return CompletableFuture.supplyAsync(() -> {
            MailMessages.Mail mail = new MailMessages(settings, ZoneId.systemDefault(), e -> Exposure.NONE).test(by);
            try {
                mailer.send(server, address, mail);
                store.log(address, null, "test", List.of(), mail.subject(), "sent", 1, null);
                return new TestResult(true, "Sent to " + address + " through " + server.host() + " port "
                    + server.port() + ". Check that it arrived, and not in a spam folder.");
            } catch (SmtpMailer.Failure f) {
                store.log(address, null, "test", List.of(), mail.subject(), "failed", 1, f.getMessage());
                return new TestResult(false, f.getMessage());
            }
        }, sends);
    }

    /**
     * Sends a confirmation code to a person's new address, on a thread of its own: once, logged;
     * they can ask for another. Returns at once.
     */
    public void sendConfirmation(long subscriberId, String address, String code) {
        MailSettings settings = settings();
        SmtpMailer.Server server = server();
        if (server == null || !settings.enabled()) {
            return;
        }
        sends.execute(() -> {
            // Everything inside the one try: an error escaping here would reach the thread's default
            // handler, which prints a stack trace outside the server's log.
            try {
                MailMessages.Mail mail = new MailMessages(settings, ZoneId.systemDefault(), e -> Exposure.NONE)
                    .confirmation(code);
                try {
                    mailer.send(server, address, mail);
                    store.log(address, subscriberId, "confirm", List.of(), mail.subject(), "sent", 1, null);
                } catch (SmtpMailer.Failure f) {
                    store.log(address, subscriberId, "confirm", List.of(), mail.subject(), "failed", 1, f.getMessage());
                }
            } catch (RuntimeException e) {
                LOG.warnf("Could not send a confirmation email (subscriber %d): %s", subscriberId, e.getMessage());
            }
        });
    }
}

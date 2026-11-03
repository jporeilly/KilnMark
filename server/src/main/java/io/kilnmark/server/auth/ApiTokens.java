package io.kilnmark.server.auth;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import io.kilnmark.server.Db;
import io.kilnmark.server.KilnmarkConfig;
import io.kilnmark.server.Role;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;

/**
 * API tokens: {@code km_} followed by 32 random bytes in base64url. Only the token's SHA-256 is
 * stored; the token is shown once, when it is created. Each token has a role, may expire, and can
 * be revoked.
 *
 * <p>On start, an api process makes sure an admin can sign in: {@code KILNMARK_ADMIN_TOKEN} is
 * stored (hashed) as an admin token, or, when it is not set and no admin token is usable, a new
 * one is written to {@code admin-token} next to the secret key file, readable only by the server's
 * user. The token itself is never logged.
 */
@ApplicationScoped
public class ApiTokens {

    private static final Logger LOG = Logger.getLogger(ApiTokens.class);

    /** Every token starts with this, so it is easy to spot in a file or a secret scanner. */
    public static final String PREFIX = "km_";
    private static final int SECRET_BYTES = 32;
    /** 32 bytes in base64url without padding. */
    private static final int MIN_SECRET_CHARS = 43;
    private static final int MAX_TOKEN_CHARS = 256;
    private static final Pattern TOKEN = Pattern.compile("km_[A-Za-z0-9_-]{" + MIN_SECRET_CHARS + ",250}");
    private static final Duration TOUCH_EVERY = Duration.ofMinutes(1);
    /** Advisory lock key (classid, objid) held while api processes check the bootstrap token. */
    private static final int LOCK_CLASS = 0x4B4D;
    private static final int LOCK_BOOTSTRAP = 0;

    static final String FROM_ENVIRONMENT = "environment";
    static final String FROM_FIRST_START = "first-start";
    /** Revocation reason for an environment token replaced on start; undone if the value returns. */
    static final String ENVIRONMENT_CHANGED = "environment-changed";
    /** The first-start token is for signing in once and creating a permanent admin token. */
    private static final Duration FIRST_TOKEN_LIFE = Duration.ofHours(24);
    /** File, next to the secret key, holding the first admin token. */
    public static final String ADMIN_TOKEN_FILE = "admin-token";

    /** A stored token, without its hash. {@code unmask}: it holds {@link AccessRole#UNMASK_GRANT}. */
    public record Token(long id, String name, AccessRole role, boolean unmask, String createdBy, Instant createdAt,
                        Instant expiresAt, Instant lastUsedAt, Instant revokedAt, String revokedReason,
                        String unmaskRequestedBy) {

        /**
         * {@code active}, {@code expired}, {@code set-aside} (an environment token replaced by a new
         * value, which comes back if that value is set again) or {@code revoked} at {@code now}.
         */
        public String status(Instant now) {
            if (revokedAt != null) {
                return ENVIRONMENT_CHANGED.equals(revokedReason) ? "set-aside" : "revoked";
            }
            return expiresAt != null && !expiresAt.isAfter(now) ? "expired" : "active";
        }
    }

    /** A token just created: {@code secret} is the whole token, shown this once and never stored. */
    public record Issued(Token token, String secret) {
    }

    /** Who presented a valid token. {@code unmask}: the token holds {@link AccessRole#UNMASK_GRANT}. */
    public record Caller(long tokenId, String name, AccessRole role, Instant expiresAt, boolean unmask) {

        /** A caller without the unmask grant. */
        public Caller(long tokenId, String name, AccessRole role, Instant expiresAt) {
            this(tokenId, name, role, expiresAt, false);
        }
    }

    private final Db db;
    private final KilnmarkConfig config;
    /** Set by CDI; null when built in a test, which then records nothing. */
    @jakarta.inject.Inject
    io.kilnmark.server.audit.AuditLog audit;
    private final SecureRandom random = new SecureRandom();
    /** When this process last wrote last_used_at for each token, so a busy token is not written every request. */
    private final Map<Long, Instant> touched = new ConcurrentHashMap<>();

    private final String httpHost;

    public ApiTokens(Db db, KilnmarkConfig config,
                     @ConfigProperty(name = "quarkus.http.host", defaultValue = "0.0.0.0") String httpHost) {
        this.db = db;
        this.config = config;
        this.httpHost = httpHost;
    }

    /** A lifetime in words: whole days where it is, else hours. */
    static String days(Duration d) {
        long hours = d.toHours();
        return hours % 24 == 0 ? hours / 24 + (hours == 24 ? " day" : " days") : hours + " hours";
    }

    /** A new random token: {@code km_} and 43 base64url characters. */
    String newSecret() {
        byte[] raw = new byte[SECRET_BYTES];
        random.nextBytes(raw);
        return PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
    }

    /** SHA-256 of the whole token. The token has 256 random bits, so a slow password hash adds nothing. */
    static byte[] hash(String token) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("This Java runtime has no SHA-256", e);
        }
    }

    /** Creates a token, with the unmask grant if it is a designer's. */
    public Issued create(String name, AccessRole role, Instant expiresAt, String createdBy) {
        return create(name, role, expiresAt, createdBy, null);
    }

    /** Letters and symbols that draw nothing: the Hangul fillers (choseong, jungseong, compatibility, half-width) and blank braille. */
    private static final java.util.Set<Integer> BLANK = java.util.Set.of(0x115F, 0x1160, 0x3164, 0xFFA0, 0x2800);

    /**
     * Whether a token name would read as a provider sign-in's ({@code sso:...}), however it is
     * written: compatibility forms folded (full-width letters and colons), accents and other marks
     * dropped, and anything before the first letter or digit ignored: spaces of every kind, and the
     * letters and symbols that draw nothing (the Hangul fillers, the blank braille pattern).
     */
    static boolean looksLikeProviderName(String name) {
        String n = java.text.Normalizer.normalize(name, java.text.Normalizer.Form.NFKC).replaceAll("\\p{M}", "");
        int i = 0;
        while (i < n.length() && (!Character.isLetterOrDigit(n.codePointAt(i)) || BLANK.contains(n.codePointAt(i)))) {
            i += Character.charCount(n.codePointAt(i));
        }
        return n.regionMatches(true, i, OidcIdentityAugmentor.PREFIX, 0, OidcIdentityAugmentor.PREFIX.length());
    }

    /**
     * Creates a token. {@code expiresAt} may be null for one that never expires. {@code unmask}
     * says whether it holds {@link AccessRole#UNMASK_GRANT}; null means yes for a designer token and
     * no for any other. Only a designer token may hold it (ADR 0027).
     */
    public Issued create(String name, AccessRole role, Instant expiresAt, String createdBy, Boolean unmask) {
        String clean = name == null ? "" : name.strip();
        if (clean.isEmpty() || clean.length() > 100) {
            throw new IllegalArgumentException("name: give the token a name of 1 to 100 characters, such as ci-pipeline");
        }
        if (clean.codePoints().anyMatch(ApiTokens::unprintable)) {
            throw new IllegalArgumentException("name: use letters, digits, spaces and punctuation only, without line "
                + "breaks or other control characters");
        }
        if (looksLikeProviderName(clean)) {
            throw new IllegalArgumentException("name: names starting " + OidcIdentityAugmentor.PREFIX + " are kept for "
                + "people who sign in with the identity provider; choose another");
        }
        if (role == null) {
            throw new IllegalArgumentException("role: use viewer, steward, designer or admin");
        }
        boolean grant = unmask == null ? role.mayHoldUnmask() : unmask;
        if (grant && !role.mayHoldUnmask()) {
            throw new IllegalArgumentException("unmask: only a designer token can see unmasked values; " + role.id()
                + " tokens always see columns marked sensitive masked");
        }
        if (expiresAt != null && !expiresAt.isAfter(Instant.now())) {
            throw new IllegalArgumentException("expiresAt: choose a time in the future, or leave it out for no expiry");
        }
        Optional<Duration> max = maxLifetime();
        // five minutes' slack: the browser works out "in 30 days" by its own clock, which may run ahead
        if (max.isPresent() && (expiresAt == null || expiresAt.isAfter(Instant.now().plus(max.get())
            .plus(Duration.ofMinutes(5))))) {
            throw new IllegalArgumentException("expiresAt: this server requires every token to expire within "
                + days(max.get()) + " (kilnmark.auth.token-max-lifetime); choose an expiry no later than that");
        }
        String secret = newSecret();
        // With approval required, the grant waits for a second admin: made without it, and asked for
        boolean waits = grant && unmaskApproval();
        Token t = limited(db.tx(c -> insert(c, clean, role, grant && !waits, hash(secret), createdBy, expiresAt,
            waits ? createdBy : null)));
        LOG.infof("API token %d (%s, %s%s) created by %s", t.id(), loggable(t.name()), t.role().id(),
            t.unmask() ? ", sees unmasked values" : "", loggable(createdBy));
        return new Issued(t, secret);
    }

    private static final String COLUMNS =
        "id, name, role, created_by, created_at, expires_at, last_used_at, revoked_at, revoked_reason, unmask, "
            + "unmask_requested_by";

    /** A token as stored. */
    private static Token row(ResultSet rs) throws SQLException {
        return new Token(rs.getLong(1), rs.getString(2), AccessRole.parse(rs.getString(3)), rs.getBoolean(10),
            rs.getString(4), Db.instant(rs.getTimestamp(5)), Db.instant(rs.getTimestamp(6)),
            Db.instant(rs.getTimestamp(7)), Db.instant(rs.getTimestamp(8)), rs.getString(9), rs.getString(11));
    }

    /** A token as it works now: its expiry no later than the longest lifetime allows (ADR 0037). */
    private Token token(ResultSet rs) throws SQLException {
        return limited(row(rs));
    }

    /** {@code t} with the expiry it really has: its own, or its creation plus the longest lifetime, whichever is sooner. */
    private Token limited(Token t) {
        Instant limited = expiry(t.createdBy(), t.createdAt(), t.expiresAt());
        return limited == t.expiresAt() ? t : new Token(t.id(), t.name(), t.role(), t.unmask(), t.createdBy(),
            t.createdAt(), limited, t.lastUsedAt(), t.revokedAt(), t.revokedReason(), t.unmaskRequestedBy());
    }

    /** The shortest and longest lifetime the setting may give: under a day no token could be made, and very long would overflow. */
    static final Duration SHORTEST_LIMIT = Duration.ofDays(1);
    static final Duration LONGEST_LIMIT = Duration.ofDays(3650);

    /** The longest a token may last, from {@code kilnmark.auth.token-max-lifetime}; empty: no limit. Checked at start. */
    public Optional<Duration> maxLifetime() {
        return config == null ? Optional.empty() : config.auth().tokenMaxLifetime();
    }

    /**
     * Refuses a longest lifetime under a day or over ten years, so a mistake (0 meant as "strict", a
     * negative number, a typo with many zeros) never quietly means no limit or fails every sign-in.
     *
     * @throws IllegalStateException saying what to change
     */
    static void checkLimit(Optional<Duration> max) {
        if (max.isPresent() && (max.get().compareTo(SHORTEST_LIMIT) < 0 || max.get().compareTo(LONGEST_LIMIT) > 0)) {
            throw new IllegalStateException("kilnmark.auth.token-max-lifetime must be between 1 day and 3650 days, for "
                + "example 90d; leave it unset for no limit.");
        }
    }

    /**
     * SQL that is true while the token {@code t} works: not revoked, and before its own expiry and the
     * longest lifetime (from its creation; the environment's admin token is not limited). The one
     * place sign-in, the admin count and notifications all ask (ADR 0037). The limit is the server's
     * own setting, written as a whole number of seconds, never a caller's value.
     */
    static String worksSql(String t, Optional<Duration> max) {
        String expiry = max.isEmpty() ? t + ".expires_at"
            : "LEAST(" + t + ".expires_at, CASE WHEN " + t + ".created_by = '" + FROM_ENVIRONMENT + "' THEN NULL ELSE "
                + t + ".created_at + make_interval(secs => " + max.get().toSeconds() + ") END)";
        return "(" + t + ".revoked_at IS NULL AND (" + expiry + " IS NULL OR " + expiry + " > now()))";
    }

    /** {@link #worksSql} with this server's limit. */
    public String worksSql(String t) {
        return worksSql(t, maxLifetime());
    }

    /**
     * When a token stops working: its own expiry, or its creation plus the longest lifetime, whichever
     * comes first. The environment's admin token is not limited: it is replaced by changing the setting.
     */
    Instant expiry(String createdBy, Instant createdAt, Instant expiresAt) {
        Optional<Duration> max = maxLifetime();
        if (max.isEmpty() || FROM_ENVIRONMENT.equals(createdBy) || createdAt == null) {
            return expiresAt;
        }
        Instant limit = createdAt.plus(max.get());
        return expiresAt == null || limit.isBefore(expiresAt) ? limit : expiresAt;
    }

    private static Token insert(Connection c, String name, AccessRole role, boolean unmask, byte[] hash,
                                String createdBy, Instant expiresAt) throws SQLException {
        return insert(c, name, role, unmask, hash, createdBy, expiresAt, null);
    }

    private static Token insert(Connection c, String name, AccessRole role, boolean unmask, byte[] hash,
                                String createdBy, Instant expiresAt, String unmaskRequestedBy) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("INSERT INTO kilnmark.api_token "
            + "(name, role, unmask, hash, created_by, expires_at, unmask_requested_by, unmask_requested_at) "
            + "VALUES (?, ?, ?, ?, ?, ?, ?, CASE WHEN ? IS NULL THEN NULL ELSE now() END) RETURNING " + COLUMNS)) {
            ps.setString(1, name);
            ps.setString(2, role.id());
            ps.setBoolean(3, unmask);
            ps.setBytes(4, hash);
            ps.setString(5, createdBy);
            ps.setTimestamp(6, Db.timestamp(expiresAt));
            ps.setString(7, unmaskRequestedBy);
            ps.setString(8, unmaskRequestedBy);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return row(rs); // as stored; create shows it limited()
            }
        }
    }

    /** Every token, newest first. Never includes hashes or secrets. */
    public List<Token> list() {
        return db.tx(c -> {
            List<Token> out = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement(
                "SELECT " + COLUMNS + " FROM kilnmark.api_token ORDER BY id DESC");
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(token(rs));
                }
            }
            return out;
        });
    }

    public Optional<Token> find(long id) {
        return db.tx(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                "SELECT " + COLUMNS + " FROM kilnmark.api_token WHERE id = ?")) {
                ps.setLong(1, id);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? Optional.of(token(rs)) : Optional.empty();
                }
            }
        });
    }

    /** Whether a second admin must approve the unmask grant (ADR 0038). */
    public boolean unmaskApproval() {
        return config != null && config.auth().unmaskApproval();
    }

    /** Why an approval or refusal of a token's unmask grant was not made. */
    public enum GrantProblem {
        /** No such token. */
        NOT_FOUND,
        /** The token asks for no grant (approved, refused, revoked, or never asked). */
        NOTHING_ASKED,
        /** The admin who asked for it may not approve it: a second person must. */
        SAME_PERSON
    }

    /** The outcome of approving or refusing a grant: the token as it now is, or why not. */
    public record GrantDecision(Token token, GrantProblem problem) {
    }

    /**
     * Gives the token the unmask grant it asked for, approved by {@code by}, who must not be the admin
     * who asked (ADR 0038). Names are compared as Kilnmark records callers: a token's {@code token N
     * (name)}, a provider sign-in's {@code sso:...}.
     */
    public GrantDecision approveUnmask(long id, String by) {
        return decideUnmask(id, by, true);
    }

    /** Clears a token's request for the unmask grant; the token stays, without it. Anyone but the asker may. */
    public GrantDecision refuseUnmask(long id, String by) {
        return decideUnmask(id, by, false);
    }

    private GrantDecision decideUnmask(long id, String by, boolean approve) {
        return db.tx(c -> {
            String asked;
            try (PreparedStatement ps = c.prepareStatement("SELECT unmask_requested_by FROM kilnmark.api_token "
                + "WHERE id = ? AND revoked_at IS NULL FOR UPDATE")) {
                ps.setLong(1, id);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        return new GrantDecision(null, GrantProblem.NOT_FOUND);
                    }
                    asked = rs.getString(1);
                }
            }
            if (asked == null) {
                return new GrantDecision(null, GrantProblem.NOTHING_ASKED);
            }
            if (approve && asked.equals(by)) {
                return new GrantDecision(null, GrantProblem.SAME_PERSON);
            }
            try (PreparedStatement ps = c.prepareStatement("UPDATE kilnmark.api_token SET unmask = ?, "
                + "unmask_requested_by = NULL, unmask_requested_at = NULL WHERE id = ? RETURNING " + COLUMNS)) {
                ps.setBoolean(1, approve);
                ps.setLong(2, id);
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    Token t = token(rs);
                    LOG.infof("Unmask grant for API token %d (%s) %s by %s", t.id(), loggable(t.name()),
                        approve ? "approved" : "refused", loggable(by));
                    return new GrantDecision(t, null);
                }
            }
        });
    }

    /** Revokes a token at once, for every server. Revoking a revoked token changes nothing. */
    public Optional<Token> revoke(long id, String by) {
        Optional<Token> t = db.tx(c -> {
            try (PreparedStatement ps = c.prepareStatement("UPDATE kilnmark.api_token "
                // An admin's revocation is final: it also turns a set-aside environment token into a
                // revoked one, so restoring an old KILNMARK_ADMIN_TOKEN value cannot bring it back.
                + "SET revoked_reason = CASE WHEN revoked_at IS NULL OR revoked_reason = ? THEN ? "
                + "ELSE revoked_reason END, revoked_at = coalesce(revoked_at, now()) WHERE id = ? RETURNING "
                + COLUMNS)) {
                ps.setString(1, ENVIRONMENT_CHANGED);
                ps.setString(2, "revoked by " + by);
                ps.setLong(3, id);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? Optional.of(token(rs)) : Optional.empty();
                }
            }
        });
        t.ifPresent(x -> LOG.infof("API token %d (%s) revoked by %s", x.id(), loggable(x.name()), loggable(by)));
        return t;
    }

    /**
     * The caller a token belongs to, if it is known, unexpired and not revoked. The stored hash is
     * compared in constant time; its last use is recorded at most once a minute.
     */
    public Optional<Caller> authenticate(String presented) {
        if (presented == null || presented.length() > MAX_TOKEN_CHARS || !presented.startsWith(PREFIX)) {
            return Optional.empty();
        }
        byte[] wanted = hash(presented);
        return db.tx(c -> {
            Caller caller;
            Instant lastUsed;
            Instant now;
            try (PreparedStatement ps = c.prepareStatement("SELECT t.id, t.name, t.role, t.hash, t.expires_at, "
                + "t.last_used_at, t.revoked_at, now(), t.unmask, t.created_by, t.created_at, " + worksSql("t")
                + " FROM kilnmark.api_token t WHERE t.hash = ?")) {
                ps.setBytes(1, wanted);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next() || !MessageDigest.isEqual(wanted, rs.getBytes(4))) {
                        return Optional.empty();
                    }
                    // whether it works is the database's answer (worksSql), as for the admin count and notifications
                    if (!rs.getBoolean(12)) {
                        return Optional.empty();
                    }
                    Instant expires = expiry(rs.getString(10), Db.instant(rs.getTimestamp(11)),
                        Db.instant(rs.getTimestamp(5)));
                    now = Db.instant(rs.getTimestamp(8));
                    caller = new Caller(rs.getLong(1), rs.getString(2), AccessRole.parse(rs.getString(3)), expires,
                        rs.getBoolean(9));
                    lastUsed = Db.instant(rs.getTimestamp(6));
                }
            }
            touch(c, caller.tokenId(), lastUsed, now);
            return Optional.of(caller);
        });
    }

    private void touch(Connection c, long id, Instant lastUsed, Instant now) throws SQLException {
        Instant mine = touched.get(id);
        if ((lastUsed != null && lastUsed.plus(TOUCH_EVERY).isAfter(now))
            || (mine != null && mine.plus(TOUCH_EVERY).isAfter(now))) {
            return;
        }
        touched.put(id, now);
        try (PreparedStatement ps = c.prepareStatement("UPDATE kilnmark.api_token SET last_used_at = now() "
            + "WHERE id = ? AND (last_used_at IS NULL OR last_used_at < now() - interval '1 minute')")) {
            ps.setLong(1, id);
            ps.executeUpdate();
        }
    }

    private static boolean unprintable(int cp) {
        return Character.isISOControl(cp) || Character.getType(cp) == Character.LINE_SEPARATOR
            || Character.getType(cp) == Character.PARAGRAPH_SEPARATOR || Character.getType(cp) == Character.FORMAT;
    }

    /** Text as it may appear in a log line: control characters (a forged line break, say) become '?'. */
    public static String loggable(String text) {
        if (text == null) {
            return "";
        }
        StringBuilder b = new StringBuilder(text.length());
        text.codePoints().forEach(cp -> b.appendCodePoint(unprintable(cp) ? '?' : cp));
        return b.toString();
    }

    // Straight after Kilnmark's own database check and migrations (DatabaseTls, priority 1), before the
    // mail and notification workers start: a bad longest lifetime stops the server before anything uses it.
    void onStart(@Observes @jakarta.annotation.Priority(2) StartupEvent event) {
        AuthMode.check(config.auth().mode(), httpHost);
        checkLimit(maxLifetime());
        if (config.auth().mode() == AuthMode.OFF) {
            LOG.warnf("Sign-in is off (kilnmark.auth.mode=off): anyone who can reach %s is an admin. Remove "
                + "KILNMARK_AUTH_MODE to sign in with API tokens.", httpHost);
            return;
        }
        if (!config.roles().contains(Role.API)) {
            return; // only api processes check tokens
        }
        bootstrap(config.adminToken().map(String::strip).filter(s -> !s.isEmpty()),
            Path.of(config.secretKeyFile()).toAbsolutePath().getParent());
    }

    /**
     * Makes sure an admin can sign in. With {@code envToken}, stores it as the admin token and
     * revokes any earlier one from the environment. Without it, revokes those and, if no usable
     * admin token is left, creates one and writes it to {@code admin-token} in {@code folder}.
     *
     * @return the file written, if one was
     */
    Optional<Path> bootstrap(Optional<String> envToken, Path folder) {
        envToken.ifPresent(t -> {
            if (!TOKEN.matcher(t).matches()) {
                throw new IllegalStateException("KILNMARK_ADMIN_TOKEN is not a usable token: it must be km_ followed "
                    + "by at least 43 letters, digits, '-' or '_'. Generate one with "
                    + "\"echo km_$(openssl rand -base64 32 | tr '+/' '-_' | tr -d '=')\", or unset it to have the "
                    + "server create a token on first start.");
            }
        });
        Path file = folder.resolve(ADMIN_TOKEN_FILE);
        // What start-up changed, for the audit log once the transaction has committed: action, target, detail.
        List<Object[]> changes = new ArrayList<>();
        Optional<Path> written = db.tx(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT pg_advisory_xact_lock(?, ?)")) {
                ps.setInt(1, LOCK_CLASS);
                ps.setInt(2, LOCK_BOOTSTRAP);
                ps.execute();
            }
            byte[] envHash = envToken.map(ApiTokens::hash).orElse(new byte[0]);
            // Set aside, not destroyed: setting the same value again brings the token back.
            try (PreparedStatement ps = c.prepareStatement("UPDATE kilnmark.api_token SET revoked_at = now(), "
                + "revoked_reason = ? WHERE created_by = ? AND revoked_at IS NULL AND hash <> ?")) {
                ps.setString(1, ENVIRONMENT_CHANGED);
                ps.setString(2, FROM_ENVIRONMENT);
                ps.setBytes(3, envHash);
                int setAside = ps.executeUpdate();
                if (setAside > 0) {
                    LOG.info("Set aside the previous KILNMARK_ADMIN_TOKEN, which is no longer set");
                    changes.add(new Object[] {io.kilnmark.server.audit.AuditLog.TOKEN_REVOKED, "KILNMARK_ADMIN_TOKEN",
                        Map.of("reason", "set aside: no longer set", "tokens", setAside)});
                }
            }
            if (envToken.isPresent()) {
                if (acceptEnvironmentToken(c, envHash)) {
                    changes.add(new Object[] {io.kilnmark.server.audit.AuditLog.TOKEN_CREATED, "KILNMARK_ADMIN_TOKEN",
                        Map.of("role", "admin", "unmask", false, "via", "environment")});
                }
            }
            if (usableAdmins(c) > 0) {
                return Optional.<Path>empty();
            }
            // Never leave the server with no way for an admin to sign in.
            String secret = newSecret();
            Token t = insert(c, "first admin", AccessRole.ADMIN, false, hash(secret), FROM_FIRST_START,
                Instant.now().plus(FIRST_TOKEN_LIFE));
            writePrivate(file, secret);
            LOG.infof("No admin API token was usable, so token %d was created. It is in %s (readable only by this "
                + "user) and works for 24 hours: sign in with it, create a permanent admin token under API tokens, "
                + "then delete the file.", t.id(), file);
            changes.add(new Object[] {io.kilnmark.server.audit.AuditLog.TOKEN_CREATED, "token " + t.id(), Map.of("name", t.name(),
                "role", "admin", "unmask", false, "expiresAt", String.valueOf(t.expiresAt()), "via", "first start")});
            return Optional.of(file);
        });
        if (audit != null) {
            for (Object[] e : changes) {
                @SuppressWarnings("unchecked")
                Map<String, Object> detail = (Map<String, Object>) e[2];
                audit.record(io.kilnmark.server.audit.AuditLog.Actor.server("start-up"), (String) e[0], (String) e[1], detail);
            }
        }
        return written;
    }

    /** Stores the environment's admin token; true when it was new or is back in use. */
    private static boolean acceptEnvironmentToken(Connection c, byte[] envHash) throws SQLException {
        boolean changed;
        try (PreparedStatement ps = c.prepareStatement("INSERT INTO kilnmark.api_token (name, role, hash, created_by) "
            + "VALUES ('KILNMARK_ADMIN_TOKEN', 'admin', ?, ?) ON CONFLICT (hash) DO NOTHING")) {
            ps.setBytes(1, envHash);
            ps.setString(2, FROM_ENVIRONMENT);
            changed = ps.executeUpdate() > 0;
        }
        // Back in use if only the start-up check had set it aside; a revocation by an admin stands.
        try (PreparedStatement ps = c.prepareStatement("UPDATE kilnmark.api_token SET revoked_at = NULL, "
            + "revoked_reason = NULL WHERE hash = ? AND created_by = ? AND revoked_reason = ?")) {
            ps.setBytes(1, envHash);
            ps.setString(2, FROM_ENVIRONMENT);
            ps.setString(3, ENVIRONMENT_CHANGED);
            if (ps.executeUpdate() > 0) {
                LOG.info("KILNMARK_ADMIN_TOKEN is set to an earlier value again, so that token works again");
                changed = true;
            }
        }
        try (PreparedStatement ps = c.prepareStatement(
            "SELECT role, created_by, revoked_at FROM kilnmark.api_token WHERE hash = ?")) {
            ps.setBytes(1, envHash);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                if (rs.getTimestamp(3) != null) {
                    LOG.warn("KILNMARK_ADMIN_TOKEN was revoked by an admin, so it is not accepted. Set it to a new "
                        + "token and restart the server.");
                } else if (!FROM_ENVIRONMENT.equals(rs.getString(2))) {
                    LOG.warnf("KILNMARK_ADMIN_TOKEN is the same as a token created through the API, so it keeps "
                        + "that token's role (%s). Set it to a new token to have an admin token.", rs.getString(1));
                }
            }
        }
        return changed;
    }

    private int usableAdmins(Connection c) throws SQLException {
        // as sign-in judges them: a token past the longest lifetime is no way in, so the first-start
        // token is written when it was the last admin
        try (PreparedStatement ps = c.prepareStatement("SELECT count(*) FROM kilnmark.api_token t WHERE t.role = "
            + "'admin' AND " + worksSql("t"));
             ResultSet rs = ps.executeQuery()) {
            rs.next();
            return rs.getInt(1);
        }
    }

    /** Writes {@code text} to {@code file} so that only the owner can read it, replacing any earlier file. */
    static void writePrivate(Path file, String text) {
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        try {
            Files.createDirectories(file.getParent());
            Files.deleteIfExists(tmp);
            try {
                Files.createFile(tmp, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
            } catch (UnsupportedOperationException e) {
                Files.createFile(tmp); // Windows: the file inherits the folder's permissions
            }
            Files.writeString(tmp, text + System.lineSeparator());
            try {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot write the first admin token to " + file
                + ". Make the folder writable by the server's user, or set KILNMARK_ADMIN_TOKEN.", e);
        }
    }
}

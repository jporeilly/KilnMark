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

    /** A stored token, without its hash. */
    public record Token(long id, String name, AccessRole role, String createdBy, Instant createdAt, Instant expiresAt,
                        Instant lastUsedAt, Instant revokedAt) {

        /** {@code active}, {@code expired} or {@code revoked} at {@code now}. */
        public String status(Instant now) {
            if (revokedAt != null) {
                return "revoked";
            }
            return expiresAt != null && !expiresAt.isAfter(now) ? "expired" : "active";
        }
    }

    /** A token just created: {@code secret} is the whole token, shown this once and never stored. */
    public record Issued(Token token, String secret) {
    }

    /** Who presented a valid token. */
    public record Caller(long tokenId, String name, AccessRole role, Instant expiresAt) {
    }

    private final Db db;
    private final KilnmarkConfig config;
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

    /** Creates a token. {@code expiresAt} may be null for one that never expires. */
    public Issued create(String name, AccessRole role, Instant expiresAt, String createdBy) {
        String clean = name == null ? "" : name.strip();
        if (clean.isEmpty() || clean.length() > 100) {
            throw new IllegalArgumentException("name: give the token a name of 1 to 100 characters, such as ci-pipeline");
        }
        if (clean.codePoints().anyMatch(ApiTokens::unprintable)) {
            throw new IllegalArgumentException("name: use letters, digits, spaces and punctuation only, without line "
                + "breaks or other control characters");
        }
        if (role == null) {
            throw new IllegalArgumentException("role: use viewer, steward or admin");
        }
        if (expiresAt != null && !expiresAt.isAfter(Instant.now())) {
            throw new IllegalArgumentException("expiresAt: choose a time in the future, or leave it out for no expiry");
        }
        String secret = newSecret();
        Token t = db.tx(c -> insert(c, clean, role, hash(secret), createdBy, expiresAt));
        LOG.infof("API token %d (%s, %s) created by %s", t.id(), loggable(t.name()), t.role().id(),
            loggable(createdBy));
        return new Issued(t, secret);
    }

    private static final String COLUMNS =
        "id, name, role, created_by, created_at, expires_at, last_used_at, revoked_at";

    private static Token token(ResultSet rs) throws SQLException {
        return new Token(rs.getLong(1), rs.getString(2), AccessRole.parse(rs.getString(3)), rs.getString(4),
            Db.instant(rs.getTimestamp(5)), Db.instant(rs.getTimestamp(6)), Db.instant(rs.getTimestamp(7)),
            Db.instant(rs.getTimestamp(8)));
    }

    private static Token insert(Connection c, String name, AccessRole role, byte[] hash, String createdBy,
                                Instant expiresAt) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("INSERT INTO kilnmark.api_token "
            + "(name, role, hash, created_by, expires_at) VALUES (?, ?, ?, ?, ?) RETURNING " + COLUMNS)) {
            ps.setString(1, name);
            ps.setString(2, role.id());
            ps.setBytes(3, hash);
            ps.setString(4, createdBy);
            ps.setTimestamp(5, Db.timestamp(expiresAt));
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return token(rs);
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

    /** Revokes a token at once, for every server. Revoking a revoked token changes nothing. */
    public Optional<Token> revoke(long id, String by) {
        Optional<Token> t = db.tx(c -> {
            try (PreparedStatement ps = c.prepareStatement("UPDATE kilnmark.api_token "
                + "SET revoked_reason = CASE WHEN revoked_at IS NULL THEN ? ELSE revoked_reason END, "
                + "revoked_at = coalesce(revoked_at, now()) WHERE id = ? RETURNING " + COLUMNS)) {
                ps.setString(1, "revoked by " + by);
                ps.setLong(2, id);
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
            try (PreparedStatement ps = c.prepareStatement("SELECT id, name, role, hash, expires_at, last_used_at, "
                + "revoked_at, now() FROM kilnmark.api_token WHERE hash = ?")) {
                ps.setBytes(1, wanted);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next() || !MessageDigest.isEqual(wanted, rs.getBytes(4))) {
                        return Optional.empty();
                    }
                    Instant expires = Db.instant(rs.getTimestamp(5));
                    now = Db.instant(rs.getTimestamp(8));
                    if (rs.getTimestamp(7) != null || (expires != null && !expires.isAfter(now))) {
                        return Optional.empty();
                    }
                    caller = new Caller(rs.getLong(1), rs.getString(2), AccessRole.parse(rs.getString(3)), expires);
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
    static String loggable(String text) {
        if (text == null) {
            return "";
        }
        StringBuilder b = new StringBuilder(text.length());
        text.codePoints().forEach(cp -> b.appendCodePoint(unprintable(cp) ? '?' : cp));
        return b.toString();
    }

    void onStart(@Observes StartupEvent event) {
        AuthMode.check(config.auth().mode(), httpHost);
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
        return db.tx(c -> {
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
                if (ps.executeUpdate() > 0) {
                    LOG.info("Set aside the previous KILNMARK_ADMIN_TOKEN, which is no longer set");
                }
            }
            if (envToken.isPresent()) {
                acceptEnvironmentToken(c, envHash);
            }
            if (usableAdmins(c) > 0) {
                return Optional.<Path>empty();
            }
            // Never leave the server with no way for an admin to sign in.
            String secret = newSecret();
            Token t = insert(c, "first admin", AccessRole.ADMIN, hash(secret), FROM_FIRST_START,
                Instant.now().plus(FIRST_TOKEN_LIFE));
            writePrivate(file, secret);
            LOG.infof("No admin API token was usable, so token %d was created. It is in %s (readable only by this "
                + "user) and works for 24 hours: sign in with it, create a permanent admin token under API tokens, "
                + "then delete the file.", t.id(), file);
            return Optional.of(file);
        });
    }

    private static void acceptEnvironmentToken(Connection c, byte[] envHash) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("INSERT INTO kilnmark.api_token (name, role, hash, created_by) "
            + "VALUES ('KILNMARK_ADMIN_TOKEN', 'admin', ?, ?) ON CONFLICT (hash) DO NOTHING")) {
            ps.setBytes(1, envHash);
            ps.setString(2, FROM_ENVIRONMENT);
            ps.executeUpdate();
        }
        // Back in use if only the start-up check had set it aside; a revocation by an admin stands.
        try (PreparedStatement ps = c.prepareStatement("UPDATE kilnmark.api_token SET revoked_at = NULL, "
            + "revoked_reason = NULL WHERE hash = ? AND created_by = ? AND revoked_reason = ?")) {
            ps.setBytes(1, envHash);
            ps.setString(2, FROM_ENVIRONMENT);
            ps.setString(3, ENVIRONMENT_CHANGED);
            if (ps.executeUpdate() > 0) {
                LOG.info("KILNMARK_ADMIN_TOKEN is set to an earlier value again, so that token works again");
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
    }

    private static int usableAdmins(Connection c) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT count(*) FROM kilnmark.api_token WHERE role = 'admin' "
            + "AND revoked_at IS NULL AND (expires_at IS NULL OR expires_at > now())");
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

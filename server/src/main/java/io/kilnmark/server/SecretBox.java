package io.kilnmark.server;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import jakarta.enterprise.context.ApplicationScoped;

/**
 * Encrypts source passwords with AES-256-GCM before they reach the database. The key comes from
 * {@code KILNMARK_SECRET_KEY}, or from a key file the server creates on first start, so a copy of
 * the database alone never reveals a password.
 */
@ApplicationScoped
public class SecretBox {

    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;

    private final SecretKeySpec key;
    private final SecureRandom random = new SecureRandom();

    public SecretBox(KilnmarkConfig config) {
        byte[] raw = config.secretKey().map(k -> Base64.getDecoder().decode(k.trim()))
            .orElseGet(() -> keyFile(Path.of(config.secretKeyFile())));
        if (raw.length != 32) {
            throw new IllegalStateException("The secret key must be 32 bytes, base64-encoded");
        }
        this.key = new SecretKeySpec(raw, "AES");
    }

    private byte[] keyFile(Path file) {
        try {
            if (Files.exists(file)) {
                return Base64.getDecoder().decode(Files.readString(file).trim());
            }
            Files.createDirectories(file.getParent());
            byte[] raw = new byte[32];
            random.nextBytes(raw);
            Files.writeString(file, Base64.getEncoder().encodeToString(raw));
            try {
                Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
            } catch (UnsupportedOperationException e) {
                // Windows: the file inherits the folder's permissions
            }
            return raw;
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read or create the secret key file " + file, e);
        }
    }

    public byte[] seal(String plaintext) {
        try {
            byte[] iv = new byte[IV_BYTES];
            random.nextBytes(iv);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            byte[] sealed = c.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            return ByteBuffer.allocate(IV_BYTES + sealed.length).put(iv).put(sealed).array();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Encryption failed", e);
        }
    }

    public String open(byte[] box) {
        try {
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, box, 0, IV_BYTES));
            return new String(c.doFinal(box, IV_BYTES, box.length - IV_BYTES), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException e) {
            throw new WrongKey(e);
        }
    }

    /**
     * A stored value was sealed with another key than this server's: most often the database was
     * restored without the keys volume from the same backup (ADR 0039). The API answers 409 with
     * the message.
     */
    public static final class WrongKey extends IllegalStateException {
        private static final long serialVersionUID = 1L;

        WrongKey(Throwable cause) {
            super(MESSAGE, cause);
        }
    }

    /** What to do when a stored value cannot be opened, in words an admin can act on. */
    public static final String MESSAGE = "A stored password cannot be opened with this server's key. If Kilnmark "
        + "was restored, restore its keys from the same backup as the database; otherwise enter the password again.";
}

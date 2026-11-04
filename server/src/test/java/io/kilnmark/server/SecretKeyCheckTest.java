package io.kilnmark.server;

import static io.kilnmark.server.Tokens.admin;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.Map;
import java.util.Optional;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.Test;

import io.agroal.api.AgroalDataSource;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;

/**
 * A database restored without its keys (ADR 0039): a stored password sealed with another key is
 * named at start, and the API says what to do (409) instead of failing with a server error.
 */
@QuarkusTest
class SecretKeyCheckTest {

    @Inject
    AgroalDataSource db;

    @Inject
    SecretKeyCheck check;

    @ConfigProperty(name = "quarkus.datasource.jdbc.url")
    String url;

    @ConfigProperty(name = "quarkus.datasource.username")
    String user;

    @Test
    void aPasswordSealedWithAnotherKeyIsNamed() throws Exception {
        String name = "wrong-key";
        admin().contentType(ContentType.JSON)
            .body(Map.of("name", name, "kind", "postgresql", "settings", Map.of("url", url, "username", user),
                "password", "not-used"))
            .post("/api/v1/sources").then().statusCode(201);
        try {
            assertEquals(Optional.empty(), check.problem(), "this server's own key opens every stored value");

            setSecret(name, sealedWithAnotherKey("not-used"));
            Optional<String> problem = check.problem();
            assertTrue(problem.isPresent() && problem.get().startsWith(SecretBox.MESSAGE), String.valueOf(problem));
            assertTrue(problem.get().contains("1 of "), problem.get());
            Tokens.designer().post("/api/v1/sources/" + name + "/test")
                .then().statusCode(409).body("error", equalTo(SecretBox.MESSAGE));
        } finally {
            admin().delete("/api/v1/sources/" + name).then().statusCode(200);
        }
        assertEquals(Optional.empty(), check.problem());
    }

    private void setSecret(String name, byte[] secret) throws Exception {
        try (Connection c = db.getConnection();
             PreparedStatement ps = c.prepareStatement("UPDATE kilnmark.data_source SET secret = ? WHERE name = ?")) {
            ps.setBytes(1, secret);
            ps.setString(2, name);
            assertEquals(1, ps.executeUpdate());
        }
    }

    /** Sealed as {@link SecretBox} seals, with a new random key: what a restore without the keys leaves. */
    private static byte[] sealedWithAnotherKey(String text) throws Exception {
        SecureRandom random = new SecureRandom();
        byte[] key = new byte[32];
        byte[] iv = new byte[12];
        random.nextBytes(key);
        random.nextBytes(iv);
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, iv));
        byte[] sealed = c.doFinal(text.getBytes(StandardCharsets.UTF_8));
        return ByteBuffer.allocate(iv.length + sealed.length).put(iv).put(sealed).array();
    }
}

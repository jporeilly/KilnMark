package io.kilnmark.server;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.cert.X509Certificate;
import java.util.Map;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLHandshakeException;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

import org.junit.jupiter.api.Test;

import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;

/**
 * Kilnmark serving HTTPS itself (ADR 0031), configured as an installation is: a certificate and its
 * key as PEM files (KILNMARK_TLS_CERT_FILE, KILNMARK_TLS_KEY_FILE). They are made for the test with
 * openssl, so no key is ever kept in the repository; the test is skipped where openssl is missing.
 */
@QuarkusTest
@TestProfile(HttpsTest.WithCertificate.class)
class HttpsTest {

    /** A self-signed certificate for localhost and its key, as PEM files, made when the test starts. */
    public static class WithCertificate implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            try {
                Path dir = Files.createTempDirectory("kilnmark-tls");
                Path cert = dir.resolve("server.crt");
                Path key = dir.resolve("server.key");
                Process p = new ProcessBuilder("openssl", "req", "-x509", "-newkey", "ec", "-pkeyopt",
                    "ec_paramgen_curve:prime256v1", "-nodes", "-days", "2", "-subj", "/CN=localhost",
                    "-addext", "subjectAltName=DNS:localhost,IP:127.0.0.1", "-keyout", key.toString(), "-out",
                    cert.toString()).redirectErrorStream(true).start();
                p.getInputStream().readAllBytes();
                if (p.waitFor() != 0) {
                    return Map.of();
                }
                return Map.of("test.https-ready", "true",
                    // Forward slashes: a config source reads backslashes as escapes (Windows paths)
                    "quarkus.http.ssl.certificate.files", cert.toString().replace('\\', '/'),
                    "quarkus.http.ssl.certificate.key-files", key.toString().replace('\\', '/'),
                    "quarkus.http.test-ssl-port", "0");
            } catch (IOException e) {
                return Map.of(); // no openssl here
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
        }
    }

    @TestHTTPResource(value = "/", tls = true)
    URL https;

    @org.eclipse.microprofile.config.inject.ConfigProperty(name = "test.https-ready", defaultValue = "false")
    boolean tls;

    @Test
    void overHttpsResponsesTellBrowsersToKeepToIt() {
        org.junit.jupiter.api.Assumptions.assumeTrue(tls, "openssl is not installed here");
        given().relaxedHTTPSValidation().get(https).then().statusCode(200)
            .header("Strict-Transport-Security", equalTo(SecurityHeaders.HSTS))
            .header("Content-Security-Policy", equalTo(SecurityHeaders.CSP));
    }

    @Test
    void onlyTls13And12AreSpoken() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(tls, "openssl is not installed here");
        assertTrue(handshake("TLSv1.3"));
        assertTrue(handshake("TLSv1.2"));
        assertThrows(SSLHandshakeException.class, () -> handshake("TLSv1.1"));
    }

    /** Connects with one protocol only, trusting the test's certificate; true when the handshake ends. */
    private boolean handshake(String protocol) throws Exception {
        SSLContext ctx = SSLContext.getInstance("TLS");
        ctx.init(null, new TrustManager[] {new X509TrustManager() {
            @Override
            public void checkClientTrusted(X509Certificate[] chain, String authType) {
            }

            @Override
            public void checkServerTrusted(X509Certificate[] chain, String authType) {
            }

            @Override
            public X509Certificate[] getAcceptedIssuers() {
                return new X509Certificate[0];
            }
        }}, null);
        try (SSLSocket s = (SSLSocket) ctx.getSocketFactory().createSocket(https.getHost(), https.getPort())) {
            s.setEnabledProtocols(new String[] {protocol});
            s.startHandshake();
            return true;
        }
    }
}

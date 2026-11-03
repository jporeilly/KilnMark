package io.kilnmark.server;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

import java.io.IOException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;

/**
 * Kilnmark serving HTTPS itself (ADR 0031): with a certificate it answers over TLS, and those
 * responses tell browsers to keep to HTTPS. The certificate is made for the test with the JDK's
 * keytool, so no key is ever kept in the repository.
 */
@QuarkusTest
@TestProfile(HttpsTest.WithCertificate.class)
class HttpsTest {

    /** A self-signed certificate for localhost, in a PKCS12 store made when the test starts. */
    public static class WithCertificate implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            try {
                Path dir = Files.createTempDirectory("kilnmark-tls");
                Path store = dir.resolve("server.p12");
                Path keytool = Path.of(System.getProperty("java.home"), "bin", "keytool");
                Process p = new ProcessBuilder(keytool.toString(), "-genkeypair", "-alias", "server", "-keyalg", "EC",
                    "-groupname", "secp256r1", "-dname", "CN=localhost", "-ext", "SAN=dns:localhost,ip:127.0.0.1",
                    "-validity", "2", "-storetype", "PKCS12", "-keystore", store.toString(), "-storepass", "test-only",
                    "-keypass", "test-only").redirectErrorStream(true).start();
                if (p.waitFor() != 0) {
                    throw new IllegalStateException(new String(p.getInputStream().readAllBytes()));
                }
                return Map.of("quarkus.http.ssl.certificate.key-store-file", store.toString(),
                    "quarkus.http.ssl.certificate.key-store-password", "test-only",
                    "quarkus.http.test-ssl-port", "0");
            } catch (IOException e) {
                throw new IllegalStateException(e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
        }
    }

    @TestHTTPResource(value = "/", tls = true)
    URL https;

    @Test
    void overHttpsResponsesTellBrowsersToKeepToIt() {
        given().relaxedHTTPSValidation().get(https).then().statusCode(200)
            .header("Strict-Transport-Security", equalTo(SecurityHeaders.HSTS))
            .header("Content-Security-Policy", equalTo(SecurityHeaders.CSP));
    }
}

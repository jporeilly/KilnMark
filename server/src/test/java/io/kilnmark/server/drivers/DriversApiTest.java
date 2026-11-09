package io.kilnmark.server.drivers;

import static io.kilnmark.server.Tokens.admin;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import com.sun.net.httpserver.HttpServer;

import io.kilnmark.server.Tokens;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;

/**
 * Settings › Drivers through the API: admins only; which connectors need which driver; accepting
 * a licence is recorded; a download that does not give the expected file installs nothing. A local
 * server stands in for Maven Central (%test.kilnmark.drivers.maven-url).
 */
@QuarkusTest
@QuarkusTestResource(value = DriversApiTest.FakeMaven.class, restrictToAnnotatedClass = true)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DriversApiTest {

    private static final String JAR = "/maven2/com/oracle/database/jdbc/ojdbc11/23.26.3.0.0/ojdbc11-23.26.3.0.0.jar";

    private static final AtomicReference<byte[]> served = FakeMaven.SERVED;

    /**
     * Stands in for Maven Central on a free loopback port, chosen when Quarkus starts for this class,
     * and points kilnmark.drivers.maven-url at it. A fixed port clashed when two runs met on one
     * host: CI runners in WSL share loopback with Windows under mirrored networking.
     */
    public static class FakeMaven implements QuarkusTestResourceLifecycleManager {
        static final AtomicReference<byte[]> SERVED = new AtomicReference<>();
        static volatile int port;
        private HttpServer server;

        @SuppressWarnings("AddressSelection") // the loopback address written out, not a host name to look up
        @Override
        public Map<String, String> start() {
            try {
                server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            } catch (java.io.IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
            server.createContext("/maven2/", exchange -> {
                byte[] body = SERVED.get();
                if (body == null || !exchange.getRequestURI().getPath().equals(JAR)) {
                    exchange.sendResponseHeaders(404, -1);
                } else {
                    exchange.sendResponseHeaders(200, body.length);
                    try (OutputStream out = exchange.getResponseBody()) {
                        out.write(body);
                    }
                }
                exchange.close();
            });
            server.start();
            port = server.getAddress().getPort();
            return Map.of("kilnmark.drivers.maven-url", "http:" + "/" + "/127.0.0.1:" + port + "/maven2/");
        }

        @Override
        public void stop() {
            if (server != null) {
                server.stop(0);
            }
        }
    }

    private static String viewerToken() {
        return admin().contentType(ContentType.JSON).body(Map.of("name", "drivers-viewer", "role", "viewer"))
            .post("/api/v1/tokens").then().statusCode(201).extract().jsonPath().getString("token");
    }

    @Test
    void onlyAdminsSeeOrInstallDrivers() {
        String viewer = viewerToken();
        Tokens.as(viewer).get("/api/v1/drivers").then().statusCode(403);
        Tokens.as(viewer).post("/api/v1/drivers/ojdbc11/accept").then().statusCode(403);
        admin().post("/api/v1/drivers/no-such-driver/accept").then().statusCode(404);
    }

    @Test
    void theOracleDriverIsListedAsNotInstalled() {
        admin().get("/api/v1/drivers").then().statusCode(200)
            .body("find { it.id == 'ojdbc11' }.coordinates", equalTo("com.oracle.database.jdbc:ojdbc11"))
            .body("find { it.id == 'ojdbc11' }.usedBy", hasItem("Oracle"))
            .body("find { it.id == 'ojdbc11' }.licence", containsString("Free Use Terms"))
            .body("find { it.id == 'ojdbc11' }.state", equalTo("missing"))
            .body("find { it.id == 'ojdbc11' }.download", equalTo(true))
            .body("find { it.id == 'ojdbc11' }.downloadUrl", equalTo("http:" + "/" + "/127.0.0.1:" + FakeMaven.port + JAR));
        admin().get("/api/v1/connectors").then().statusCode(200)
            .body("find { it.kind == 'oracle' }.driver", equalTo("missing"))
            .body("find { it.kind == 'oracle' }.driverBundled", equalTo(false))
            // the tile names the exact driver to install, the version Kilnmark is tested with
            .body("find { it.kind == 'oracle' }.driverName", equalTo("ojdbc11"))
            .body("find { it.kind == 'oracle' }.driverVersion", equalTo(io.kilnmark.kiln.plugins.jdbc.OracleConnector.DRIVER.version()))
            .body("find { it.kind == 'postgresql' }.driver", equalTo("bundled"))
            .body("find { it.kind == 'postgresql' }.driverName", org.hamcrest.Matchers.nullValue())
            .body("find { it.kind == 'postgresql' }.driverVersion", org.hamcrest.Matchers.nullValue());
    }

    @Test
    void acceptingIsRecordedAndAWrongDownloadInstallsNothing() throws Exception {
        served.set(null);
        admin().post("/api/v1/drivers/ojdbc11/accept").then().statusCode(502)
            .body("error", containsString("answered 404"))
            .body("error", containsString("Or put ojdbc11-23.26.3.0.0.jar in "));
        admin().get("/api/v1/drivers").then().statusCode(200)
            .body("find { it.id == 'ojdbc11' }.acceptedBy", notNullValue())
            .body("find { it.id == 'ojdbc11' }.acceptedAt", startsWith("20"));

        served.set("not the driver".getBytes(StandardCharsets.UTF_8));
        admin().post("/api/v1/drivers/ojdbc11/accept").then().statusCode(502)
            .body("error", containsString("is not the expected file"));
        String folder = admin().get("/api/v1/drivers").then().statusCode(200)
            .body("find { it.id == 'ojdbc11' }.state", equalTo("missing"))
            .body("find { it.id == 'ojdbc11' }.sha256", startsWith("764cc3f8"))
            .extract().jsonPath().getString("find { it.id == 'ojdbc11' }.folder");
        try (var files = Files.list(Path.of(folder))) {
            assertEquals(List.of(), files.toList(), "nothing is left in the drivers folder");
        }
    }

    @Test
    void aDriverOfAnotherKindIsNotAskedFor() {
        admin().get("/api/v1/drivers").then().statusCode(200)
            .body("find { it.id == 'mssql-jdbc' }", nullValue());
    }
}

package io.kilnmark.server.relations;

import static io.kilnmark.server.Tokens.admin;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.Test;

import io.agroal.api.AgroalDataSource;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.restassured.path.json.JsonPath;
import jakarta.inject.Inject;

/**
 * The reference lab's answer key (lab/README.md): billing.transactions.customer_id has no foreign
 * key to crm.customers and must come out as a strong proposal; crm.accounts to crm.customers is
 * declared. The lab's own SQL is loaded into a database of its own.
 */
@QuarkusTest
class LabRelationshipsTest {

    @Inject
    AgroalDataSource db;

    @ConfigProperty(name = "quarkus.datasource.jdbc.url")
    String url;

    @ConfigProperty(name = "quarkus.datasource.username")
    String user;

    @ConfigProperty(name = "quarkus.datasource.password")
    String password;

    @Test
    @SuppressWarnings("unchecked")
    void theLabsUndeclaredJoinIsAStrongProposal() throws Exception {
        try (Connection c = db.getConnection(); Statement s = c.createStatement()) {
            // A reused test database may hold the lab from an earlier run: start again.
            s.execute("DROP DATABASE IF EXISTS rel_lab WITH (FORCE)");
            s.execute("CREATE DATABASE rel_lab");
        }
        String labUrl = url.replaceFirst("/[^/?]+(\\?|$)", "/rel_lab$1");
        Path lab = Path.of(System.getProperty("user.dir")).getParent().resolve("lab/postgres");
        try (Connection c = DriverManager.getConnection(labUrl, user, password); Statement s = c.createStatement()) {
            s.execute(Files.readString(lab.resolve("01-schema.sql")));
            s.execute(Files.readString(lab.resolve("02-data.sql")));
        }
        admin().contentType(ContentType.JSON).body(Map.of("name", "rel-lab", "kind", "postgresql", "password", password,
                "settings", Map.of("url", labUrl, "username", user, "schemas", "crm,billing")))
            .post("/api/v1/sources").then().statusCode(org.hamcrest.Matchers.oneOf(201, 409));
        long id = admin().post("/api/v1/sources/rel-lab/scans").then().statusCode(202).extract().jsonPath().getLong("id");
        JsonPath run = null;
        for (int i = 0; i < 480; i++) {
            run = admin().get("/api/v1/runs/" + id).then().statusCode(200).extract().jsonPath();
            if (!Set.of("queued", "running").contains(run.getString("status"))) {
                break;
            }
            Thread.sleep(250);
        }
        assertEquals("succeeded", run.getString("status"), run.getString("detail"));

        List<Map<String, Object>> rels = admin().queryParam("asset", "urn:kilnmark:rel-lab:crm.customers")
            .get("/api/v1/relationships").then().statusCode(200).extract().jsonPath().getList("items");
        Map<String, Object> txn = one(rels, "urn:kilnmark:rel-lab:billing.transactions", "customer_id");
        assertNotNull(txn, rels.toString());
        assertEquals("inferred", txn.get("kind"));
        assertEquals("proposed", txn.get("status"));
        assertTrue(((Number) txn.get("confidence")).doubleValue() >= 0.9, txn.toString());
        assertEquals(List.of("customer_id"), ((Map<String, Object>) txn.get("to")).get("fields"));

        Map<String, Object> accounts = one(rels, "urn:kilnmark:rel-lab:crm.accounts", "customer_id");
        assertNotNull(accounts, rels.toString());
        assertEquals("declared", accounts.get("kind"));
        assertEquals("accepted", accounts.get("status"));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> one(List<Map<String, Object>> rels, String from, String field) {
        return rels.stream().filter(r -> ((Map<String, Object>) r.get("from")).get("urn").equals(from)
                && ((List<String>) ((Map<String, Object>) r.get("from")).get("fields")).equals(List.of(field))
                && ((Map<String, Object>) r.get("to")).get("urn").equals("urn:kilnmark:rel-lab:crm.customers"))
            .findFirst().orElse(null);
    }
}

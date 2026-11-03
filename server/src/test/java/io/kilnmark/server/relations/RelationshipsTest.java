package io.kilnmark.server.relations;

import static io.kilnmark.server.Tokens.admin;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import io.agroal.api.AgroalDataSource;
import io.kilnmark.kiln.connect.CatalogInfo;
import io.kilnmark.kiln.connect.Connectors;
import io.kilnmark.kiln.connect.RelationshipInfo;
import io.kilnmark.kiln.connect.SourceConfig;
import io.kilnmark.server.Tokens;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.restassured.path.json.JsonPath;
import jakarta.inject.Inject;

/**
 * Relationships end to end on PostgreSQL: foreign keys read from the catalogue (single, composite
 * in key order, across schemas), an undeclared join proposed from names and sketches, browsing,
 * diagrams, decisions by role, and rescans that keep people's decisions.
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class RelationshipsTest {

    private static final String SOURCE = "rel-db";
    private static final String CUSTOMERS = "urn:kilnmark:rel-db:rel_crm.customers";
    private static final String ACCOUNTS = "urn:kilnmark:rel-db:rel_crm.accounts";
    private static final String TRANSACTIONS = "urn:kilnmark:rel-db:rel_billing.transactions";
    private static final String PAYMENTS = "urn:kilnmark:rel-db:rel_billing.payments";

    @Inject
    AgroalDataSource db;

    @Inject
    Connectors connectors;

    @ConfigProperty(name = "quarkus.datasource.jdbc.url")
    String url;

    @ConfigProperty(name = "quarkus.datasource.username")
    String user;

    @ConfigProperty(name = "quarkus.datasource.password")
    String password;

    private static boolean loaded;
    private static String viewer;
    private static String steward;

    @BeforeEach
    void load() throws Exception {
        if (loaded) {
            return;
        }
        sql("CREATE SCHEMA rel_crm",
            "CREATE SCHEMA rel_billing",
            "CREATE TABLE rel_crm.customers (customer_id bigint PRIMARY KEY, name text NOT NULL)",
            "INSERT INTO rel_crm.customers SELECT n, 'Customer ' || n FROM generate_series(1, 500) n",
            """
            CREATE TABLE rel_crm.accounts (account_id bigint PRIMARY KEY,
                customer_id bigint NOT NULL CONSTRAINT accounts_customer_fk REFERENCES rel_crm.customers)""",
            "INSERT INTO rel_crm.accounts SELECT n, 1 + n % 500 FROM generate_series(1, 800) n",
            // A composite key, referred to in a different column order than the key's own.
            "CREATE TABLE rel_crm.branches (region text, branch_no integer, name text, PRIMARY KEY (region, branch_no))",
            "INSERT INTO rel_crm.branches VALUES ('north', 1, 'Leeds'), ('south', 1, 'Bristol')",
            """
            CREATE TABLE rel_crm.staff (staff_id integer PRIMARY KEY, b_no integer, reg text,
                CONSTRAINT staff_branch_fk FOREIGN KEY (b_no, reg) REFERENCES rel_crm.branches (branch_no, region))""",
            "INSERT INTO rel_crm.staff VALUES (1, 1, 'north'), (2, 1, 'south')",
            // Across schemas: declared to accounts; customer_id with no key at all.
            """
            CREATE TABLE rel_billing.transactions (txn_id bigint PRIMARY KEY, customer_id bigint NOT NULL,
                account_id bigint CONSTRAINT txn_account_fk REFERENCES rel_crm.accounts)""",
            "INSERT INTO rel_billing.transactions SELECT n, 1 + n % 450, 1 + n % 800 FROM generate_series(1, 3000) n",
            "CREATE TABLE rel_billing.payments (payment_id bigint PRIMARY KEY, customer_id bigint NOT NULL)",
            "INSERT INTO rel_billing.payments SELECT n, 1 + n % 300 FROM generate_series(1, 600) n",
            "CREATE VIEW rel_billing.big_spenders AS SELECT customer_id FROM rel_billing.transactions");
        viewer = token("rel-viewer", "viewer");
        steward = token("rel-steward", "steward");
        loaded = true;
    }

    private void sql(String... statements) throws Exception {
        try (Connection c = db.getConnection(); Statement s = c.createStatement()) {
            for (String q : statements) {
                s.execute(q);
            }
        }
    }

    private static String token(String name, String role) {
        return admin().contentType(ContentType.JSON).body(Map.of("name", name, "role", role))
            .post("/api/v1/tokens").then().statusCode(201).extract().jsonPath().getString("token");
    }

    private static JsonPath scan() throws InterruptedException {
        long id = admin().post("/api/v1/sources/" + SOURCE + "/scans").then().statusCode(202)
            .extract().jsonPath().getLong("id");
        for (int i = 0; i < 240; i++) {
            JsonPath run = admin().get("/api/v1/runs/" + id).then().statusCode(200).extract().jsonPath();
            if (!Set.of("queued", "running").contains(run.getString("status"))) {
                assertEquals("succeeded", run.getString("status"), run.getString("detail"));
                return run;
            }
            Thread.sleep(250);
        }
        throw new AssertionError("scan " + id + " did not finish");
    }

    private static List<Map<String, Object>> relationshipsOf(String urn) {
        return admin().queryParam("asset", urn).get("/api/v1/relationships").then().statusCode(200)
            .extract().jsonPath().getList("items");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> find(List<Map<String, Object>> rels, String from, String field, String to) {
        return rels.stream().filter(r -> ((Map<String, Object>) r.get("from")).get("urn").equals(from)
                && ((List<String>) ((Map<String, Object>) r.get("from")).get("fields")).contains(field)
                && ((Map<String, Object>) r.get("to")).get("urn").equals(to))
            .findFirst().orElse(null);
    }

    @Test
    @Order(1)
    void theConnectorReadsForeignKeysInKeyOrderAndAcrossSchemas() throws Exception {
        CatalogInfo found = connectors.get("postgresql").orElseThrow().catalogWithRelationships(new SourceConfig(
            "probe", "postgresql", Map.of("url", url, "username", user, "schemas", "rel_crm,rel_billing"), password));
        Map<String, RelationshipInfo> byName = found.relationships().stream()
            .collect(Collectors.toMap(RelationshipInfo::name, r -> r));
        assertEquals(Set.of("accounts_customer_fk", "staff_branch_fk", "txn_account_fk"), byName.keySet());
        RelationshipInfo staff = byName.get("staff_branch_fk");
        assertEquals("rel_crm.staff", staff.fromPath());
        assertEquals(List.of("b_no", "reg"), staff.fromFields());
        assertEquals("rel_crm.branches", staff.toPath());
        assertEquals(List.of("branch_no", "region"), staff.toFields());
        RelationshipInfo txn = byName.get("txn_account_fk");
        assertEquals("rel_billing.transactions", txn.fromPath());
        assertEquals("rel_crm.accounts", txn.toPath());
        assertEquals(List.of("account_id"), txn.toFields());

        // A key to a schema that is not read is left out: its table is not in the catalogue.
        CatalogInfo billingOnly = connectors.get("postgresql").orElseThrow().catalogWithRelationships(new SourceConfig(
            "probe", "postgresql", Map.of("url", url, "username", user, "schemas", "rel_billing"), password));
        assertTrue(billingOnly.relationships().isEmpty());
        assertEquals(found.assets().stream().filter(a -> a.container().equals("rel_billing")).count(),
            billingOnly.assets().size());
    }

    @Test
    @Order(2)
    void aScanRecordsDeclaredKeysAndProposesTheUndeclaredJoin() throws Exception {
        admin().contentType(ContentType.JSON).body(Map.of("name", SOURCE, "kind", "postgresql", "password", password,
                "settings", Map.of("url", url, "username", user, "schemas", "rel_crm,rel_billing")))
            .post("/api/v1/sources").then().statusCode(201);
        JsonPath run = scan();
        assertTrue(run.getString("detail").contains("proposed for review"), run.getString("detail"));

        List<Map<String, Object>> txn = relationshipsOf(TRANSACTIONS);
        Map<String, Object> declared = find(txn, TRANSACTIONS, "account_id", ACCOUNTS);
        assertNotNull(declared, txn.toString());
        assertEquals("declared", declared.get("kind"));
        assertEquals("accepted", declared.get("status"));
        assertEquals("txn_account_fk", declared.get("name"));

        Map<String, Object> inferred = find(txn, TRANSACTIONS, "customer_id", CUSTOMERS);
        assertNotNull(inferred, txn.toString());
        assertEquals("inferred", inferred.get("kind"));
        double confidence = ((Number) inferred.get("confidence")).doubleValue();
        assertTrue(confidence >= 0.9, inferred.toString());
        // At 95% or more an inferred join is accepted without a person, and says so; below, it is proposed.
        if (confidence >= io.kilnmark.graph.store.RelationshipStore.AUTO_ACCEPT) {
            assertEquals("accepted", inferred.get("status"));
            assertEquals(io.kilnmark.graph.store.RelationshipStore.AUTO_ACCEPTED_BY, inferred.get("decidedBy"));
        } else {
            assertEquals("proposed", inferred.get("status"));
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> evidence = (Map<String, Object>) inferred.get("evidence");
        assertTrue(((Number) evidence.get("containment")).doubleValue() > 0.97, evidence.toString());
        assertEquals("primary key", evidence.get("unique"));
        assertEquals(450, ((Number) evidence.get("fromDistinct")).intValue());

        // The declared key from accounts stays declared, though inference finds it too.
        Map<String, Object> accounts = find(relationshipsOf(CUSTOMERS), ACCOUNTS, "customer_id", CUSTOMERS);
        assertEquals("declared", accounts.get("kind"));

        // Sketches are kept apart from the profile, one per column.
        admin().get("/api/v1/assets/" + TRANSACTIONS).then().statusCode(200)
            .body("fields.find { it.name == 'customer_id' }.profile", org.hamcrest.Matchers.not(
                org.hamcrest.Matchers.hasKey("hll")));
        assertEquals(1, count("SELECT count(*) FROM kilnmark.profile WHERE asset_urn = '" + TRANSACTIONS
            + "' AND field_name = 'customer_id' AND hll IS NOT NULL"));
        assertEquals(0, count("SELECT count(*) FROM kilnmark.profile WHERE asset_urn = '" + TRANSACTIONS
            + "' AND field_name = 'amount' AND hll IS NOT NULL"), "only key-like columns keep a sketch");
    }

    private int count(String query) throws Exception {
        try (Connection c = db.getConnection(); Statement s = c.createStatement(); ResultSet rs = s.executeQuery(query)) {
            rs.next();
            return rs.getInt(1);
        }
    }

    @Test
    @Order(3)
    void browsesSourcesContainersAndAssetsWithCounts() {
        JsonPath sources = Tokens.as(viewer).get("/api/v1/browse/sources").then().statusCode(200).extract().jsonPath();
        Map<String, Object> mine = sources.getMap("find { it.name == '" + SOURCE + "' }");
        assertEquals(2, mine.get("containers"));
        assertEquals(7, mine.get("assets"));
        assertNotNull(mine.get("label"));

        Tokens.as(viewer).get("/api/v1/browse/sources/" + SOURCE + "/containers").then().statusCode(200)
            .body("total", equalTo(2))
            .body("items.name", equalTo(List.of("rel_billing", "rel_crm")))
            .body("items[0].assets", equalTo(3))
            .body("items[0].kinds.view", equalTo(1))
            .body("items[1].kinds.table", equalTo(4));
        Tokens.as(viewer).queryParam("q", "staff").get("/api/v1/browse/sources/" + SOURCE + "/containers")
            .then().statusCode(200).body("items.name", equalTo(List.of("rel_crm"))).body("items[0].assets", equalTo(1));

        Tokens.as(viewer).queryParam("container", "rel_crm").queryParam("limit", 2).queryParam("offset", 1)
            .get("/api/v1/browse/sources/" + SOURCE + "/assets").then().statusCode(200)
            .body("total", equalTo(4))
            .body("items.name", equalTo(List.of("branches", "customers")))
            .body("items[1].format", equalTo("table"))
            .body("items[1].rowCount", equalTo(500))
            .body("items[1].fields", equalTo(2));
        Tokens.as(viewer).queryParam("container", "rel_billing").queryParam("q", "SPEND")
            .get("/api/v1/browse/sources/" + SOURCE + "/assets").then().statusCode(200)
            .body("items.format", equalTo(List.of("view")));

        Tokens.as(viewer).get("/api/v1/browse/sources/nope/containers").then().statusCode(404);
        Tokens.as(viewer).get("/api/v1/browse/sources/" + SOURCE + "/assets").then().statusCode(400)
            .body("error", containsString("container"));
        Tokens.as(viewer).queryParam("limit", 0).get("/api/v1/browse/sources/" + SOURCE + "/containers")
            .then().statusCode(400);
        Tokens.as(viewer).queryParam("q", "x".repeat(201)).get("/api/v1/browse/sources/" + SOURCE + "/containers")
            .then().statusCode(400).body("error", containsString("200"));
    }

    @Test
    @Order(4)
    void diagramsShowTablesColumnsAndRelationships() {
        JsonPath d = Tokens.as(viewer).queryParam("source", SOURCE).queryParam("container", "rel_billing")
            .get("/api/v1/diagram").then().statusCode(200).extract().jsonPath();
        assertEquals(3, d.getInt("total"));
        assertFalse(d.getBoolean("truncated"));
        List<String> names = d.getList("tables.name");
        assertTrue(names.containsAll(List.of("transactions", "payments", "big_spenders", "customers", "accounts")),
            names.toString());
        assertTrue(d.getBoolean("tables.find { it.name == 'customers' }.external"));
        assertFalse(d.getBoolean("tables.find { it.name == 'transactions' }.external"));
        assertTrue(d.getBoolean("tables.find { it.name == 'transactions' }.columns.find { it.name == 'txn_id' }.primaryKey"));
        assertTrue(d.getBoolean("tables.find { it.name == 'transactions' }.columns.find { it.name == 'account_id' }.foreignKey"));
        assertTrue(d.getBoolean("tables.find { it.name == 'transactions' }.columns.find { it.name == 'customer_id' }.foreignKey"));
        assertTrue(d.getList("relationships.kind").containsAll(List.of("declared", "inferred")));

        Tokens.as(viewer).queryParam("source", SOURCE).queryParam("container", "rel_crm").queryParam("limit", 2)
            .get("/api/v1/diagram").then().statusCode(200)
            .body("total", equalTo(4)).body("truncated", equalTo(true));

        JsonPath around = Tokens.as(viewer).queryParam("asset", CUSTOMERS).get("/api/v1/diagram")
            .then().statusCode(200).extract().jsonPath();
        // The view's customer_id fits customers too, so it is proposed like any other column. Other
        // test classes' sources share this database and may be related across sources: count ours.
        assertEquals(Set.of("customers", "accounts", "transactions", "payments", "big_spenders"),
            Set.copyOf(around.getList("tables.findAll { it.source == '" + SOURCE + "' }.name")));
        JsonPath further = Tokens.as(viewer).queryParam("asset", ACCOUNTS).queryParam("depth", 2)
            .get("/api/v1/diagram").then().statusCode(200).extract().jsonPath();
        assertTrue(further.getList("tables.name").contains("payments"), "two steps away through customers");

        Tokens.as(viewer).queryParam("urn", CUSTOMERS).queryParam("urn", ACCOUNTS).get("/api/v1/diagram")
            .then().statusCode(200).body("tables.name", equalTo(List.of("customers", "accounts")))
            .body("relationships.size()", equalTo(1));

        Tokens.as(viewer).get("/api/v1/diagram").then().statusCode(400);
        Tokens.as(viewer).queryParam("asset", "urn:kilnmark:rel-db:rel_crm.nothing").get("/api/v1/diagram")
            .then().statusCode(404);
        Tokens.as(viewer).queryParam("asset", CUSTOMERS).queryParam("depth", 3).get("/api/v1/diagram")
            .then().statusCode(400);
    }

    @Test
    @Order(5)
    void stewardsDecideViewersOnlyRead() {
        Map<String, Object> inferred = find(relationshipsOf(TRANSACTIONS), TRANSACTIONS, "customer_id", CUSTOMERS);
        long id = ((Number) inferred.get("id")).longValue();
        Map<String, Object> declared = find(relationshipsOf(TRANSACTIONS), TRANSACTIONS, "account_id", ACCOUNTS);
        long declaredId = ((Number) declared.get("id")).longValue();

        Tokens.as(viewer).get("/api/v1/relationships/" + id).then().statusCode(200)
            .body("status", org.hamcrest.Matchers.oneOf("proposed", "accepted"));
        Tokens.as(viewer).post("/api/v1/relationships/" + id + "/accept").then().statusCode(403);
        Tokens.as(steward).post("/api/v1/relationships/" + id + "/accept").then().statusCode(200)
            .body("status", equalTo("accepted")).body("decidedBy", equalTo("rel-steward"))
            .body("decidedAt", org.hamcrest.Matchers.notNullValue());
        Tokens.as(steward).post("/api/v1/relationships/" + declaredId + "/reject").then().statusCode(409)
            .body("error", containsString("declared"));
        Tokens.as(steward).post("/api/v1/relationships/999999999/reject").then().statusCode(404);

        Tokens.as(viewer).queryParam("source", SOURCE).queryParam("status", "proposed")
            .get("/api/v1/relationships").then().statusCode(200)
            .body("items.status", org.hamcrest.Matchers.everyItem(equalTo("proposed")));
        Tokens.as(viewer).queryParam("kind", "declared").queryParam("source", SOURCE)
            .get("/api/v1/relationships").then().statusCode(200).body("total", equalTo(3));
        Tokens.as(viewer).queryParam("status", "maybe").get("/api/v1/relationships").then().statusCode(400);
        int all = Tokens.as(viewer).queryParam("asset", CUSTOMERS).get("/api/v1/relationships").then().statusCode(200)
            .extract().jsonPath().getInt("total");
        assertTrue(all >= 4, "customers has several relationships: " + all);
        Tokens.as(viewer).queryParam("asset", CUSTOMERS).queryParam("limit", 1).queryParam("offset", 1)
            .get("/api/v1/relationships").then().statusCode(200)
            .body("total", equalTo(all)).body("items.size()", equalTo(1)).body("offset", equalTo(1));
        io.restassured.RestAssured.given().get("/api/v1/relationships").then().statusCode(401);
    }

    @Test
    @Order(6)
    void aRescanKeepsDecisionsAndDropsWhatNoLongerHolds() throws Exception {
        Map<String, Object> payments = find(relationshipsOf(PAYMENTS), PAYMENTS, "customer_id", CUSTOMERS);
        assertNotNull(payments, "payments.customer_id was proposed too");
        // Reject the transactions proposal; then make the payments one false and drop the composite key.
        long txnId = ((Number) find(relationshipsOf(TRANSACTIONS), TRANSACTIONS, "customer_id", CUSTOMERS).get("id"))
            .longValue();
        Tokens.as(steward).post("/api/v1/relationships/" + txnId + "/reject").then().statusCode(200)
            .body("status", equalTo("rejected"));
        sql("UPDATE rel_billing.payments SET customer_id = customer_id + 100000",
            "ALTER TABLE rel_crm.staff DROP CONSTRAINT staff_branch_fk");

        scan();

        Tokens.as(viewer).get("/api/v1/relationships/" + txnId).then().statusCode(200)
            .body("status", equalTo("rejected")).body("decidedBy", equalTo("rel-steward"));
        assertEquals(null, find(relationshipsOf(PAYMENTS), PAYMENTS, "customer_id", CUSTOMERS),
            "a proposal whose values no longer fit is withdrawn");
        Tokens.as(viewer).queryParam("kind", "declared").queryParam("source", SOURCE)
            .get("/api/v1/relationships").then().statusCode(200).body("total", equalTo(2))
            .body("items.name", org.hamcrest.Matchers.not(hasItem("staff_branch_fk")));
        // Rejected relationships are hidden from diagrams' neighbourhoods.
        JsonPath around = Tokens.as(viewer).queryParam("asset", CUSTOMERS).get("/api/v1/diagram")
            .then().statusCode(200).extract().jsonPath();
        assertFalse(around.getList("tables.name").contains("transactions"));
        assertEquals(1, count("SELECT count(*) FROM kilnmark.profile WHERE asset_urn = '" + TRANSACTIONS
            + "' AND field_name = 'customer_id' AND hll IS NOT NULL"), "only the latest profile keeps its sketch");
    }

    /**
     * A join across two sources is not proposed when it is already there within one: the same table
     * read through two sources, or a target the first source holds too.
     */
    @Test
    void joinsAcrossSourcesThatRepeatOneWithinASourceAreNotProposed() {
        java.util.function.BiFunction<String, String, io.kilnmark.kiln.plugins.relate.KeyInference.Column> col =
            (urn, field) -> new io.kilnmark.kiln.plugins.relate.KeyInference.Column(urn,
                urn.split(":")[2], urn.substring(urn.lastIndexOf('.') + 1), field, "int64", false, true, 10, 0, 10);
        java.util.function.BiFunction<String, String, io.kilnmark.kiln.plugins.relate.KeyInference.Proposal> join =
            (from, to) -> new io.kilnmark.kiln.plugins.relate.KeyInference.Proposal(col.apply(from, "account_id"),
                col.apply(to, "account_id"), 0.99, null);
        List<io.kilnmark.graph.store.RelationshipStore.KeyColumn> columns = List.of(
            new io.kilnmark.graph.store.RelationshipStore.KeyColumn("urn:kilnmark:a:crm.accounts", "a", "accounts",
                "account_id", "int64", true, true, 10, 0, 10));
        // The same table through two sources
        assertTrue(RelationshipService.acrossSourcesForNothing(
            join.apply("urn:kilnmark:a:crm.accounts", "urn:kilnmark:b:crm.accounts"), columns));
        // Source a holds crm.accounts itself: its join from billing.transactions is within a
        assertTrue(RelationshipService.acrossSourcesForNothing(
            join.apply("urn:kilnmark:a:billing.transactions", "urn:kilnmark:b:crm.accounts"), columns));
        // A target only the other source holds is a real join across sources
        assertFalse(RelationshipService.acrossSourcesForNothing(
            join.apply("urn:kilnmark:a:billing.transactions", "urn:kilnmark:b:crm.branches"), columns));
        // Within one source nothing is dropped
        assertFalse(RelationshipService.acrossSourcesForNothing(
            join.apply("urn:kilnmark:a:billing.transactions", "urn:kilnmark:a:crm.accounts"), columns));
    }

    @Inject
    RelationshipService service;

    @Inject
    io.kilnmark.graph.store.RelationshipStore store;

    @Inject
    io.kilnmark.graph.store.GraphStore graph;

    @Test
    @Order(7)
    void refreshesRunOneAtATimeAndConcurrentOnesDoNotDeadlock() throws Exception {
        // The lock: a second holder waits for the first.
        java.util.concurrent.atomic.AtomicLong firstOut = new java.util.concurrent.atomic.AtomicLong();
        java.util.concurrent.atomic.AtomicLong secondIn = new java.util.concurrent.atomic.AtomicLong();
        java.util.concurrent.CountDownLatch inside = new java.util.concurrent.CountDownLatch(1);
        Thread first = Thread.ofVirtual().start(() -> store.serialised(() -> {
            inside.countDown();
            try {
                Thread.sleep(400);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            firstOut.set(System.nanoTime());
            return null;
        }, java.time.Duration.ofSeconds(30)));
        inside.await();
        store.serialised(() -> {
            secondIn.set(System.nanoTime());
            return null;
        }, java.time.Duration.ofSeconds(30));
        first.join();
        assertTrue(secondIn.get() >= firstOut.get(), "the second refresh waited for the first");

        // Two sources over the same tables, refreshed at the same time, again and again.
        admin().contentType(ContentType.JSON).body(Map.of("name", "rel-db2", "kind", "postgresql", "password", password,
                "settings", Map.of("url", url, "username", user, "schemas", "rel_billing")))
            .post("/api/v1/sources").then().statusCode(201);
        long id = admin().post("/api/v1/sources/rel-db2/scans").then().statusCode(202).extract().jsonPath().getLong("id");
        for (int i = 0; i < 240 && Set.of("queued", "running").contains(
            admin().get("/api/v1/runs/" + id).then().extract().jsonPath().getString("status")); i++) {
            Thread.sleep(250);
        }
        var one = graph.source(SOURCE).orElseThrow();
        var two = graph.source("rel-db2").orElseThrow();
        List<Throwable> failures = java.util.Collections.synchronizedList(new java.util.ArrayList<>());
        List<Thread> threads = new java.util.ArrayList<>();
        for (var s : List.of(one, two, one, two)) {
            threads.add(Thread.ofVirtual().start(() -> {
                try {
                    for (int i = 0; i < 3; i++) {
                        service.afterScan(s, List.of(), false);
                    }
                } catch (Throwable t) {
                    failures.add(t);
                }
            }));
        }
        for (Thread t : threads) {
            t.join();
        }
        assertTrue(failures.isEmpty(), failures.toString());
        // The rejected proposal is still rejected, and rel-db2's copy of transactions is proposed too.
        assertEquals("rejected", find(relationshipsOf(TRANSACTIONS), TRANSACTIONS, "customer_id", CUSTOMERS).get("status"));
        assertNotNull(find(relationshipsOf(CUSTOMERS), "urn:kilnmark:rel-db2:rel_billing.transactions", "customer_id",
            CUSTOMERS));
    }

    @Test
    @Order(8)
    void aRefreshThatCannotGetTheLockInTimeIsSkipped() throws Exception {
        java.util.concurrent.CountDownLatch inside = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        Thread holder = Thread.ofVirtual().start(() -> store.serialised(() -> {
            inside.countDown();
            try {
                release.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return null;
        }, java.time.Duration.ofSeconds(30)));
        inside.await();
        try {
            org.junit.jupiter.api.Assertions.assertThrows(io.kilnmark.graph.store.RelationshipStore.Busy.class,
                () -> store.serialised(() -> "ran", java.time.Duration.ofMillis(300)));
            RelationshipService.Outcome skipped = service.afterScan(graph.source(SOURCE).orElseThrow(), List.of(), false,
                java.time.Duration.ofMillis(300));
            assertNotNull(skipped.skipped());
            assertTrue(skipped.skipped().contains("not refreshed"), skipped.skipped());
        } finally {
            release.countDown();
            holder.join();
        }
        assertEquals("ran", store.serialised(() -> "ran", java.time.Duration.ofSeconds(5)), "the lock is free again");
    }

    @Test
    @Order(9)
    void unreadableForeignKeysKeepTheOnesRecordedBefore() throws Exception {
        FlakyKeysConnector.failKeys = false;
        admin().contentType(ContentType.JSON).body(Map.of("name", "rel-flaky", "kind", "test-flaky-keys",
                "password", password, "settings", Map.of("url", url, "username", user, "schemas", "rel_crm")))
            .post("/api/v1/sources").then().statusCode(201);
        try {
            scanOf("rel-flaky");
            int before = Tokens.as(viewer).queryParam("kind", "declared").queryParam("source", "rel-flaky")
                .get("/api/v1/relationships").then().statusCode(200).extract().jsonPath().getInt("total");
            assertTrue(before >= 1, "declared keys recorded: " + before);

            FlakyKeysConnector.failKeys = true;
            JsonPath run = scanOf("rel-flaky");
            assertTrue(run.getString("detail").contains("Foreign keys could not be read"), run.getString("detail"));
            Tokens.as(viewer).queryParam("kind", "declared").queryParam("source", "rel-flaky")
                .get("/api/v1/relationships").then().statusCode(200).body("total", equalTo(before));
        } finally {
            FlakyKeysConnector.failKeys = false;
        }
    }

    private static JsonPath scanOf(String name) throws InterruptedException {
        long id = admin().post("/api/v1/sources/" + name + "/scans").then().statusCode(202)
            .extract().jsonPath().getLong("id");
        for (int i = 0; i < 240; i++) {
            JsonPath run = admin().get("/api/v1/runs/" + id).then().statusCode(200).extract().jsonPath();
            if (!Set.of("queued", "running").contains(run.getString("status"))) {
                assertEquals("succeeded", run.getString("status"), run.getString("detail"));
                return run;
            }
            Thread.sleep(250);
        }
        throw new AssertionError("scan " + id + " did not finish");
    }
}

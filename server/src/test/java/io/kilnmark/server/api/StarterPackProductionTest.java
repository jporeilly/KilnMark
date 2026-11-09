package io.kilnmark.server.api;

import static io.kilnmark.server.Tokens.admin;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.eclipse.microprofile.jwt.JsonWebToken;
import org.junit.jupiter.api.Test;

import io.kilnmark.server.audit.AuditLog;
import io.kilnmark.server.auth.OidcAccess;
import io.kilnmark.server.starter.ModeCheck;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.restassured.http.ContentType;
import io.restassured.path.json.JsonPath;

/**
 * A production install (ADR 0051): the Starter Pack is never turned on, through the API either,
 * and the refusal is recorded; nor are the samples offered; anyone in the pack's group is refused
 * sign-in, each refusal recorded; the templates are there as on any install; a database migrated
 * before this start (an install from before there were modes) never records a mode, and production is
 * never recorded where the Starter Pack's database or login is. On a database of its own: the mode is
 * recorded at the first start.
 */
@QuarkusTest
@TestProfile(StarterPackProductionTest.Production.class)
class StarterPackProductionTest {

    public static class Production implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            Map<String, String> out = new HashMap<>(Map.of("kilnmark.mode", "production",
                // its own database: the other tests' records the mode as starter-pack
                "quarkus.datasource.devservices.db-name", "kilnmark_production",
                "kilnmark.auth.oidc.viewer-groups", "kilnmark-viewers",
                "kilnmark.auth.oidc.admin-groups", "kilnmark-admins"));
            // the same where the tests share one database server, named outside (no dev services): a
            // new database of its own beside the shared one
            String shared = System.getenv("QUARKUS_DATASOURCE_JDBC_URL");
            if (shared != null && !shared.isBlank()) {
                String db = "kilnmark_production_test";
                try (java.sql.Connection c = java.sql.DriverManager.getConnection(shared,
                    System.getenv("QUARKUS_DATASOURCE_USERNAME"), System.getenv("QUARKUS_DATASOURCE_PASSWORD"));
                     java.sql.Statement st = c.createStatement()) {
                    st.execute("DROP DATABASE IF EXISTS " + db + " WITH (FORCE)");
                    st.execute("CREATE DATABASE " + db);
                } catch (java.sql.SQLException e) {
                    throw new IllegalStateException("A database of its own for the production tests could not be made", e);
                }
                out.put("quarkus.datasource.jdbc.url", shared.replaceFirst("/[^/?]+(\\?|$)","/" + db + "$1"));
            }
            return out;
        }
    }

    @jakarta.inject.Inject
    jakarta.enterprise.event.Event<io.quarkus.oidc.SecurityEvent> signIns;

    @jakarta.inject.Inject
    AuditLog audit;

    @jakarta.inject.Inject
    ModeCheck modes;

    @jakarta.inject.Inject
    io.kilnmark.server.KilnmarkConfig config;

    /** The example data's kind of source is never offered, added or imported on production. */
    @Test
    void theExampleDataIsNeverASourceHereNorOfferedInAddConnection() {
        List<String> kinds = admin().get("/api/v1/connectors").then().statusCode(200).extract().jsonPath().getList("kind");
        assertFalse(kinds.contains(ModeCheck.SAMPLE_DATA_KIND), kinds.toString());
        assertTrue(kinds.contains("postgresql"), kinds.toString());
        String catalogue = admin().get("/api/v1/connectors/catalogue").then().statusCode(200).extract().asString();
        assertFalse(catalogue.contains("\"" + ModeCheck.SAMPLE_DATA_KIND + "\""), catalogue);
        admin().contentType(ContentType.JSON).body(Map.of("name", "my-samples", "kind", ModeCheck.SAMPLE_DATA_KIND,
            "settings", Map.of())).post("/api/v1/sources").then().statusCode(400)
            .body("error", containsString("a production install, which never has it"));
        JsonPath imported = admin().contentType(ContentType.JSON)
            .body("[{\"name\": \"my-samples\", \"kind\": \"" + ModeCheck.SAMPLE_DATA_KIND + "\"}]")
            .post("/api/v1/sources/import").then().statusCode(422).extract().jsonPath();
        assertTrue(imported.getString("rows[0].error").contains("a production install, which never has it"),
            imported.prettify());
        admin().get("/api/v1/sources/my-samples").then().statusCode(404);
        // and one that reached the store all the same (from before, say) is never read: no connector here
        if (store.source("leftover-samples").isEmpty()) {
            store.addSource(new io.kilnmark.graph.store.Records.NewSource("leftover-samples", ModeCheck.SAMPLE_DATA_KIND, Map.of(),
                null, "left over"));
        }
        String yaml = "kilnmark: pipeline/v1\nname: reads-leftover-samples\nnodes:\n  - id: r\n    type: read\n"
            + "    source: leftover-samples\n    asset: crm/customers.csv\n";
        JsonPath v = admin().contentType(ContentType.JSON).body(Map.of("yaml", yaml)).post("/api/v1/pipelines/validate")
            .then().statusCode(200).extract().jsonPath();
        assertFalse(v.getBoolean("valid"), v.prettify());
        assertTrue(v.getString("problems.message").contains("no connector for sources of kind 'sample-data'"), v.prettify());
    }

    @jakarta.inject.Inject
    io.kilnmark.graph.store.GraphStore store;

    @Test
    void thePackIsNeverTurnedOnAndTheRefusalIsRecorded() throws Exception {
        admin().get("/api/v1/starter-pack").then().statusCode(200).body("mode", equalTo("production"))
            .body("on", equalTo(false));
        admin().get("/api/v1/starter-pack/details").then().statusCode(200).body("mode", equalTo("production"));
        int before = StarterPackApiTest.events(AuditLog.STARTER_PACK_ADDED);
        admin().post("/api/v1/starter-pack").then().statusCode(409)
            .body("error", containsString("production install"))
            .body("error", containsString("Nothing was changed"));
        // no way round it: the old force flag does nothing
        admin().post("/api/v1/starter-pack?force=true").then().statusCode(409);
        StarterPackApiTest.eventually(AuditLog.STARTER_PACK_ADDED, before + 2);
        assertEquals("production", admin().get("/api/v1/audit?action=" + AuditLog.STARTER_PACK_ADDED + "&limit=1").then()
            .statusCode(200).extract().jsonPath().getString("events[0].detail.refused"), "the refusal is recorded");
        admin().get("/api/v1/sources/" + StarterPack.DATABASE_SOURCE).then().statusCode(404);
        // nor the example schedule
        admin().get("/api/v1/schedules?pipeline=" + StarterPack.EXAMPLE_PIPELINE).then().statusCode(200)
            .body("size()", equalTo(0));
        // and no example people to record
        admin().contentType(ContentType.JSON).body(Map.of("action", "add", "people", List.of("vera.lane@kilnmark.com")))
            .post("/api/v1/starter-pack/people").then().statusCode(409);
    }

    private static Map<String, Object> made(String person, String groups) {
        return Map.of("person", person, "groups", groups);
    }

    @Test
    void whatThePeopleCommandDidIsRecordedNeverWithAPasswordAndNeverAnExampleAddress() throws Exception {
        int added = StarterPackApiTest.events(AuditLog.PERSON_ADDED);
        int imports = StarterPackApiTest.events(AuditLog.PEOPLE_IMPORTED);
        // people add (install's first admin): one event; anything else in the body, a password say, is never kept
        admin().contentType(ContentType.JSON).body(Map.of("via", "add", "made", List.of(made("rita.okoro@bank.example",
            "kilnmark-admins")), "kept", List.of(), "refused", 0, "password", "never-kept-0123456789"))
            .post("/api/v1/auth/people").then().statusCode(204);
        // people import: one event for each person made, and one for the import
        admin().contentType(ContentType.JSON).body(Map.of("via", "import", "made", List.of(
            made("vera.lane@example.com", "kilnmark-viewers"),
            made("priya.shah@example.com", "kilnmark-designers;kilnmark-stewards")),
            "kept", List.of("sam.okafor@example.com"), "refused", 0)).post("/api/v1/auth/people").then().statusCode(204);
        StarterPackApiTest.eventually(AuditLog.PERSON_ADDED, added + 3);
        StarterPackApiTest.eventually(AuditLog.PEOPLE_IMPORTED, imports + 1);
        JsonPath ev = admin().get("/api/v1/audit?action=" + AuditLog.PERSON_ADDED + "&limit=3").then().statusCode(200)
            .extract().jsonPath();
        assertEquals(Map.of("person", "rita.okoro@bank.example", "groups", List.of("kilnmark-admins"), "via", "add"),
            ev.getMap("events[2].detail"));
        assertEquals(List.of("kilnmark-designers", "kilnmark-stewards"), ev.getList("events[0].detail.groups"));
        assertTrue(!ev.prettify().contains("never-kept"), "no password");
        JsonPath im = admin().get("/api/v1/audit?action=" + AuditLog.PEOPLE_IMPORTED + "&limit=1").then().statusCode(200)
            .extract().jsonPath();
        assertEquals(Map.of("made", 2, "alreadyThere", 1, "refused", 0), im.getMap("events[0].detail"));
        // only addresses of the organisation's own, only Kilnmark's groups, no admin from a file, only admins
        for (String person : List.of("vera.lane@kilnmark.com", "someone@kilnmark.com", "someone@eu.kilnmark.com",
            "Rita@Bank.example", "not-an-address")) {
            admin().contentType(ContentType.JSON).body(Map.of("via", "add", "made", List.of(made(person, "kilnmark-viewers"))))
                .post("/api/v1/auth/people").then().statusCode(400);
            admin().contentType(ContentType.JSON).body(Map.of("via", "import", "kept", List.of(person)))
                .post("/api/v1/auth/people").then().statusCode(400);
        }
        for (String groups : List.of("kilnmark-starter-pack", "", "kilnmark-viewers;kilnmark-viewers", "admins")) {
            admin().contentType(ContentType.JSON).body(Map.of("via", "add", "made", List.of(made("rita@bank.example", groups))))
                .post("/api/v1/auth/people").then().statusCode(400);
        }
        admin().contentType(ContentType.JSON).body(Map.of("via", "import", "made", List.of(made("rita@bank.example",
            "kilnmark-viewers;kilnmark-admins")))).post("/api/v1/auth/people").then().statusCode(400);
        // on production, someone who sees sensitive values never comes from a file either
        admin().contentType(ContentType.JSON).body(Map.of("via", "import", "made", List.of(made("rita@bank.example",
            "kilnmark-designers;kilnmark-sensitive-data")))).post("/api/v1/auth/people").then().statusCode(400);
        // activate and expire: one person activated; anyone disabled for an unused password
        int activatedBefore = StarterPackApiTest.events(AuditLog.PERSON_ACTIVATED);
        int disabledBefore = StarterPackApiTest.events(AuditLog.PERSON_DISABLED);
        admin().contentType(ContentType.JSON).body(Map.of("via", "activate", "activated", List.of("ada@bank.example")))
            .post("/api/v1/auth/people").then().statusCode(204);
        admin().contentType(ContentType.JSON).body(Map.of("via", "expire", "disabled", List.of("ben@bank.example",
            "cy@bank.example"))).post("/api/v1/auth/people").then().statusCode(204);
        StarterPackApiTest.eventually(AuditLog.PERSON_ACTIVATED, activatedBefore + 1);
        StarterPackApiTest.eventually(AuditLog.PERSON_DISABLED, disabledBefore + 2);
        for (Map<String, Object> bad : List.<Map<String, Object>>of(
            Map.of("via", "activate", "activated", List.of("a@bank.example", "b@bank.example")),
            Map.of("via", "activate", "activated", List.of("vera.lane@kilnmark.com")),
            Map.of("via", "expire", "disabled", List.of("x@kilnmark.com")),
            Map.of("via", "expire", "made", List.of(made("a@bank.example", "kilnmark-viewers"))),
            Map.of("via", "add", "activated", List.of("a@bank.example")))) {
            admin().contentType(ContentType.JSON).body(bad).post("/api/v1/auth/people").then().statusCode(400);
        }
        admin().contentType(ContentType.JSON).body(Map.of("via", "reset", "made", List.of(made("rita@bank.example",
            "kilnmark-viewers")))).post("/api/v1/auth/people").then().statusCode(400);
        admin().contentType(ContentType.JSON).body(Map.of("via", "add", "made", List.of(made("a@bank.example",
            "kilnmark-viewers"), made("b@bank.example", "kilnmark-viewers")))).post("/api/v1/auth/people").then()
            .statusCode(400);
        io.kilnmark.server.Tokens.as(io.kilnmark.server.Tokens.newToken("designer", false)).contentType(ContentType.JSON)
            .body(Map.of("via", "add", "made", List.of(made("rita@bank.example", "kilnmark-viewers"))))
            .post("/api/v1/auth/people").then().statusCode(403);
        assertEquals(added + 3, StarterPackApiTest.events(AuditLog.PERSON_ADDED), "nothing refused was recorded");
        assertEquals(activatedBefore + 1, StarterPackApiTest.events(AuditLog.PERSON_ACTIVATED));
        // and a production install lets them sign in: in kilnmark-admins, not the pack's group
        assertNull(OidcAccess.decide(config, token("f1", "rita.okoro@bank.example", "kilnmark-admins"), Instant.now())
            .refusal());
    }

    @Test
    void theTemplatesAreThereAsOnAnyInstall() {
        List<String> names = admin().get("/api/v1/templates").then().statusCode(200).extract().jsonPath().getList("name");
        assertTrue(names.containsAll(List.of("template-copy-table", "template-run-in-order")), names.toString());
    }

    /** A provider sign-in as {@code user}, in {@code groups}, with two factors, signed in now. */
    private static JsonWebToken token(String sub, String user, String... groups) {
        Map<String, Object> claims = new HashMap<>();
        claims.put("iss", "https:" + "/" + "/idp.example.test/realms/kilnmark");
        claims.put("sub", sub);
        claims.put("preferred_username", user);
        claims.put("email", user);
        claims.put("amr", List.of("pwd", "otp"));
        claims.put("auth_time", Instant.now().getEpochSecond());
        claims.put("groups", List.of(groups));
        return new JsonWebToken() {
            @Override public String getName() { return user; }
            @Override public Set<String> getClaimNames() { return claims.keySet(); }
            @Override
            @SuppressWarnings({"unchecked", "TypeParameterUnusedInFormals"})
            public <T> T getClaim(String name) {
                return (T) claims.get(name);
            }
        };
    }

    @Test
    void theExamplePeopleAreRefusedSignInByThePacksGroup() throws Exception {
        Instant now = Instant.now();
        // a member of the pack's group, whatever their name
        assertEquals(OidcAccess.STARTER_GROUP_ON_PRODUCTION, OidcAccess.decide(config,
            token("s1", "someone@bank.example", "kilnmark-admins", OidcAccess.STARTER_GROUP), now).refusal());
        assertEquals(OidcAccess.STARTER_GROUP_ON_PRODUCTION, OidcAccess.decide(config,
            token("s2", "someone@bank.example", "kilnmark-viewers", "/" + OidcAccess.STARTER_GROUP), now).refusal());
        // and by a user name or email at kilnmark.com, outside the group too
        assertEquals(OidcAccess.STARTER_GROUP_ON_PRODUCTION, OidcAccess.decide(config, token("s3", "Alex.Morgan@kilnmark.com",
            "kilnmark-admins"), now).refusal());
        assertEquals(OidcAccess.STARTER_GROUP_ON_PRODUCTION, OidcAccess.decide(config, token("s3b", "someone@eu.kilnmark.com",
            "kilnmark-viewers"), now).refusal());
        // a real person
        assertNull(OidcAccess.decide(config, token("s4", "rita@bank.example", "kilnmark-admins"), now).refusal());

        // each refusal is recorded, as the sign-in events record every refused sign-in
        var identity = io.quarkus.security.runtime.QuarkusSecurityIdentity.builder()
            .setPrincipal(token("s5", "priya.shah@kilnmark.com", "kilnmark-viewers", OidcAccess.STARTER_GROUP))
            .addCredential(new io.quarkus.oidc.IdTokenCredential("id-token")).build();
        signIns.fire(new io.quarkus.oidc.SecurityEvent(io.quarkus.oidc.SecurityEvent.Type.OIDC_LOGIN, identity));
        audit.awaitStored(5000);
        JsonPath in = admin().get("/api/v1/audit?action=" + AuditLog.SIGN_IN_REFUSED + "&limit=1").then().statusCode(200)
            .extract().jsonPath();
        assertEquals(OidcAccess.STARTER_GROUP_ON_PRODUCTION, in.getString("events[0].detail.reason"), in.prettify());
    }

    @Test
    void onlyANewDatabaseRecordsAModeAndProductionNeverWhereTheStarterPackHasBeen() throws Exception {
        io.agroal.api.AgroalDataSource source = io.quarkus.arc.Arc.container().instance(io.agroal.api.AgroalDataSource.class)
            .get();
        // this run's database was new: marked before its first migration, and the mark used up by the mode
        try (java.sql.Connection c = source.getConnection()) {
            assertFalse(io.kilnmark.server.DatabaseTls.isNew(c), "migrated now");
            assertFalse(ModeCheck.markedNew(c, io.kilnmark.server.DatabaseTls.NEW_DATABASE), "the mark is used up");
            assertTrue(markThere(c, ModeCheck.INSTALL_ID), "an install id, made at the first start");
        }
        // a database not marked new (migrated before, with no mode) never records one: it predates modes
        String key = "deployment_mode_test_" + System.nanoTime();
        for (String mode : List.of(ModeCheck.PRODUCTION, ModeCheck.STARTER_PACK)) {
            String refused = modes.checkFor(mode, key, new ModeCheck.FirstStart(false, false));
            assertTrue(refused != null && refused.contains("predates deployment modes") && refused.contains("install Kilnmark afresh"),
                refused);
        }
        // a first start that fails after migrating (the mark made, no mode recorded), then a second that succeeds
        try (java.sql.Connection c = source.getConnection()) {
            io.kilnmark.server.DatabaseTls.markNew(c, key);
        }
        assertNull(modes.checkFor(ModeCheck.PRODUCTION, key, new ModeCheck.FirstStart(false, false)));
        try (java.sql.Connection c = source.getConnection()) {
            assertFalse(ModeCheck.markedNew(c, key), "used up");
            assertTrue(markThere(c, key + "_install_id"));
        }
        // a database whose mode an earlier build recorded with no install id: one is made, said and audited
        String older = "deployment_mode_test_" + System.nanoTime();
        try (java.sql.Connection c = source.getConnection(); java.sql.PreparedStatement ps = c.prepareStatement(
            "INSERT INTO kilnmark.server_setting (key, value, updated_by) VALUES (?, 'production', 'an earlier build')")) {
            ps.setString(1, older);
            ps.executeUpdate();
        }
        // the first start's record of the mode is audited too
        String fresh = "deployment_mode_test_" + System.nanoTime();
        try (java.sql.Connection c = source.getConnection()) {
            io.kilnmark.server.DatabaseTls.markNew(c, fresh);
        }
        int recorded = StarterPackApiTest.events(AuditLog.MODE_RECORDED);
        assertNull(modes.checkFor(ModeCheck.PRODUCTION, fresh, new ModeCheck.FirstStart(false, false)));
        StarterPackApiTest.eventually(AuditLog.MODE_RECORDED, recorded + 1);
        int made = StarterPackApiTest.events(AuditLog.INSTALL_ID_MADE);
        assertNull(modes.checkFor(ModeCheck.PRODUCTION, older, new ModeCheck.FirstStart(false, false)));
        StarterPackApiTest.eventually(AuditLog.INSTALL_ID_MADE, made + 1);
        try (java.sql.Connection c = source.getConnection()) {
            assertTrue(markThere(c, older + "_install_id"));
        }
        assertNull(modes.checkFor(ModeCheck.PRODUCTION, older, new ModeCheck.FirstStart(false, false)));
        assertEquals(made + 1, StarterPackApiTest.events(AuditLog.INSTALL_ID_MADE), "made once");
        // from then on the other mode is refused, either way round
        assertNull(modes.checkFor(ModeCheck.PRODUCTION, key, new ModeCheck.FirstStart(false, true)), "recorded: checked only");
        assertTrue(modes.checkFor(ModeCheck.STARTER_PACK, key, new ModeCheck.FirstStart(false, false))
            .contains("never comes to a production install"));
        // production is never first recorded where the Starter Pack's database or login is
        try (java.sql.Connection c = io.quarkus.arc.Arc.container().instance(io.agroal.api.AgroalDataSource.class).get()
            .getConnection(); java.sql.Statement st = c.createStatement()) {
            st.execute("DO $$ BEGIN IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'kilnmark_samples') THEN "
                + "CREATE ROLE kilnmark_samples LOGIN; END IF; END $$");
        }
        String other = "deployment_mode_test_" + System.nanoTime();
        try (java.sql.Connection c = source.getConnection()) {
            io.kilnmark.server.DatabaseTls.markNew(c, other);
        }
        String refused = modes.checkFor(ModeCheck.PRODUCTION, other, new ModeCheck.FirstStart(false, true));
        assertTrue(refused != null && refused.contains("already has the Starter Pack's database or login"), refused);
        try (java.sql.Connection c = source.getConnection()) {
            assertTrue(ModeCheck.markedNew(c, other), "a refused first start keeps the mark");
        }
        assertNull(modes.checkFor(ModeCheck.STARTER_PACK, other, new ModeCheck.FirstStart(false, true)),
            "a Starter Pack install may be");
        // a refused start is recorded in the audit log before the server stops
        int before = StarterPackApiTest.events(AuditLog.START_REFUSED);
        IllegalStateException e = org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
            () -> modes.refuse(refused));
        assertEquals(refused, e.getMessage());
        StarterPackApiTest.eventually(AuditLog.START_REFUSED, before + 1);
        assertEquals(refused, admin().get("/api/v1/audit?action=" + AuditLog.START_REFUSED + "&limit=1").then().statusCode(200)
            .extract().jsonPath().getString("events[0].detail.reason"));
    }

    @Test
    void theSamplesAreNeverOfferedHereNorAddedNorTheirTablesMadeAndEachRefusalIsRecorded() throws Exception {
        int before = StarterPackApiTest.events(AuditLog.SAMPLES_REFUSED);
        admin().get("/api/v1/samples/offered").then().statusCode(200).body("offered", equalTo(false))
            .body("allowed", equalTo(false));
        admin().contentType(ContentType.JSON).body(Map.of("offered", true)).put("/api/v1/samples/offered").then()
            .statusCode(409).body("error", containsString("production install"));
        admin().get("/api/v1/samples/offered").then().statusCode(200).body("offered", equalTo(false));
        admin().get("/api/v1/samples").then().statusCode(403).body("error", containsString("production install"));
        admin().contentType(ContentType.JSON).body(Map.of("sources", Map.of("crm-db", "x"))).post("/api/v1/samples").then()
            .statusCode(403);
        admin().contentType(ContentType.JSON).body(Map.of("source", "x", "schema", "public")).post("/api/v1/samples/tables")
            .then().statusCode(403);
        // each refusal is an audit event of its own, saying what was asked (asking whether they are
        // offered is not a refusal)
        StarterPackApiTest.eventually(AuditLog.SAMPLES_REFUSED, before + 4);
        JsonPath ev = admin().get("/api/v1/audit?action=" + AuditLog.SAMPLES_REFUSED + "&limit=4").then().statusCode(200)
            .extract().jsonPath();
        assertEquals(java.util.Set.of("offered", "list", "add", "tables"), java.util.Set.copyOf(ev.getList("events.detail.request")));
        assertEquals(List.of("production", "production", "production", "production"), ev.getList("events.detail.refused"));
        // the templates stay
        admin().get("/api/v1/templates").then().statusCode(200);
    }

    /**
     * A first start stopped part way through its migrations (Flyway commits each one), then a second
     * start: the mark, made before the first migration, is still there, so the mode is recorded. A
     * database migrated with no mark (from before there were modes) has none.
     */
    @Test
    void aFirstStartStoppedPartWayThroughItsMigrationsStillRecordsTheModeAtTheNext() throws Exception {
        io.agroal.api.AgroalDataSource source = io.quarkus.arc.Arc.container().instance(io.agroal.api.AgroalDataSource.class)
            .get();
        String url;
        String stopped = "km_mark_stopped_" + System.nanoTime() % 1_000_000;
        String unmarked = "km_mark_unmarked_" + System.nanoTime() % 1_000_000;
        try (java.sql.Connection c = source.getConnection(); java.sql.Statement st = c.createStatement()) {
            url = c.getMetaData().getURL();
            st.execute("CREATE DATABASE " + stopped);
            st.execute("CREATE DATABASE " + unmarked);
        }
        org.eclipse.microprofile.config.Config cfg = org.eclipse.microprofile.config.ConfigProvider.getConfig();
        String user = cfg.getValue("quarkus.datasource.username", String.class);
        String password = cfg.getValue("quarkus.datasource.password", String.class);
        java.util.function.Function<String, String> at = db -> url.replaceFirst("/[^/?]+(\\?|$)", "/" + db + "$1");
        // the first start: as the server starts, stopped after the fifth migration
        io.kilnmark.server.DatabaseTls.Connect toStopped = () -> java.sql.DriverManager.getConnection(at.apply(stopped), user,
            password);
        io.kilnmark.server.DatabaseTls.markThenMigrate(toStopped,
            () -> flyway(at.apply(stopped), user, password).target("5").load().migrate());
        // the second start: history there, so not new; the mark is kept, and the migrations finish
        try (java.sql.Connection c = toStopped.open()) {
            assertFalse(io.kilnmark.server.DatabaseTls.isNew(c), "the migrations it did are there");
            assertTrue(ModeCheck.markedNew(c, io.kilnmark.server.DatabaseTls.NEW_DATABASE), "still marked new");
        }
        io.kilnmark.server.DatabaseTls.markThenMigrate(toStopped, () -> flyway(at.apply(stopped), user, password).load().migrate());
        // a database migrated by a build from before modes, with no mark
        flyway(at.apply(unmarked), user, password).load().migrate();
        try (java.sql.Connection c = java.sql.DriverManager.getConnection(at.apply(stopped), user, password);
             java.sql.Connection u = java.sql.DriverManager.getConnection(at.apply(unmarked), user, password)) {
            assertTrue(ModeCheck.markedNew(c, io.kilnmark.server.DatabaseTls.NEW_DATABASE));
            assertFalse(ModeCheck.markedNew(u, io.kilnmark.server.DatabaseTls.NEW_DATABASE), "never marked");
        } finally {
            try (java.sql.Connection c = source.getConnection(); java.sql.Statement st = c.createStatement()) {
                st.execute("DROP DATABASE IF EXISTS " + stopped + " WITH (FORCE)");
                st.execute("DROP DATABASE IF EXISTS " + unmarked + " WITH (FORCE)");
            }
        }
    }

    private static org.flywaydb.core.api.configuration.FluentConfiguration flyway(String url, String user, String password) {
        return org.flywaydb.core.Flyway.configure().dataSource(url, user, password).schemas("kilnmark")
            .defaultSchema("kilnmark").locations("classpath:db/migration");
    }

    private static boolean markThere(java.sql.Connection c, String key) throws java.sql.SQLException {
        try (java.sql.PreparedStatement ps = c.prepareStatement("SELECT 1 FROM kilnmark.server_setting WHERE key = ?")) {
            ps.setString(1, key);
            try (java.sql.ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }
}

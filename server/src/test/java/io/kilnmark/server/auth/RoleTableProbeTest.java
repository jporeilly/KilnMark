package io.kilnmark.server.auth;

import static io.kilnmark.server.Tokens.admin;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import org.junit.jupiter.api.Test;

import io.kilnmark.server.Tokens;
import io.kilnmark.server.pipelines.MemoryConnector;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;

/**
 * Tries every line of the access review's "What each role can do" ({@link RoleTable}) as a viewer,
 * a steward, a designer, a designer holding the sensitive-data grant and an admin, and fails when
 * the server lets anyone do more or less than the line says. Each line has one probe: a request that
 * the server refuses (403) for want of the role or grant, and otherwise answers some other way (often
 * 404, for something that is not there). {@code RoleTableTest} checks the lines against
 * {@code @RolesAllowed}; this checks the checks made in the code too.
 */
@QuarkusTest
class RoleTableProbeTest {

    private static final String NONE = "987654321";
    private static final AtomicInteger ROWS = new AtomicInteger(20);

    private record Caller(String label, AccessRole role, boolean grant, String token) {
    }

    private static RequestSpecification as(String token) {
        return Tokens.as(token).contentType(ContentType.JSON);
    }

    /** What the server last answered, for the failure message. */
    private static String last = "";

    /** Whether the server let the caller through: anything but 401 and 403. */
    private static boolean through(Response r) {
        String body = r.asString();
        last = r.statusCode() + " " + (body.length() > 200 ? body.substring(0, 200) : body);
        return r.statusCode() != 401 && r.statusCode() != 403;
    }

    /** Whether the server did it: a 2xx answer, for a probe whose request is meant to succeed. */
    private static boolean done(Response r) {
        through(r);
        return r.statusCode() >= 200 && r.statusCode() < 300;
    }

    private static void scan(String source) throws InterruptedException {
        long scan = admin().post("/api/v1/sources/" + source + "/scans").then().statusCode(202).extract().jsonPath()
            .getLong("id");
        long deadline = System.nanoTime() + Duration.ofSeconds(90).toNanos();
        String status = "queued";
        while (System.nanoTime() < deadline && !"succeeded".equals(status)) {
            Thread.sleep(250);
            status = admin().get("/api/v1/runs/" + scan).then().extract().jsonPath().getString("status");
        }
        assertEquals("succeeded", status, "scan of " + source);
    }

    private static void source(String name) throws InterruptedException {
        as(Tokens.ADMIN).body(Map.of("name", name, "kind", MemoryConnector.KIND, "settings", Map.of("rows", "10"),
            "password", "probe-secret-1")).post("/api/v1/sources").then().statusCode(201);
        scan(name);
        as(Tokens.ADMIN).body(Map.of("field", "name", "sensitive", true))
            .put("/api/v1/quality/asset/sensitive?urn=urn:kilnmark:" + name + ":lab.people").then().statusCode(204);
    }

    @Test
    void eachLineAllowsWhomItSaysAndNobodyElse() throws Exception {
        String suffix = Long.toString(System.nanoTime(), 36);
        String marked = "rt-marked-" + suffix;
        String gone = "rt-gone-" + suffix;
        source(marked);
        source(gone);
        String markedUrn = "urn:kilnmark:" + marked + ":lab.people";
        // the samples are offered, so that a designer reaches what the role decides
        as(Tokens.ADMIN).body(Map.of("offered", true)).put("/api/v1/samples/offered").then()
            .statusCode(org.hamcrest.Matchers.oneOf(200, 204));

        Map<String, Function<String, Boolean>> probes = new LinkedHashMap<>();
        probes.put("read", t -> through(as(t).get("/api/v1/search?q=probe")));
        probes.put("assistant", t -> through(as(t).body(Map.of("question", "probe")).post("/api/v1/search/ask")));
        probes.put("data", t -> through(as(t).get("/api/v1/quality/asset/samples?urn=urn:kilnmark:none:x")));
        probes.put("table-sql", t -> through(as(t).body(Map.of()).post("/api/v1/sources/rt-none/explore/sql")));
        probes.put("run", t -> through(as(t).body(Map.of()).post("/api/v1/pipeline-runs/" + NONE + "/cancel")));
        probes.put("check-sources", t -> through(as(t).body(Map.of()).post("/api/v1/sources/rt-none/test")));
        probes.put("quality", t -> through(as(t).body(Map.of("status", "open")).put("/api/v1/quality/issues/" + NONE)));
        probes.put("mark", t -> through(as(t).body(Map.of("field", "x", "sensitive", true))
            .put("/api/v1/quality/asset/sensitive?urn=urn:kilnmark:none:x")));
        probes.put("relationships", t -> through(as(t).body(Map.of()).post("/api/v1/relationships/" + NONE + "/accept")));
        probes.put("read-variables", t -> through(as(t).get("/api/v1/variables")));
        probes.put("build", t -> through(as(t).delete("/api/v1/pipelines/rt-none")));
        probes.put("schedules", t -> through(as(t).delete("/api/v1/schedules/" + NONE)));
        probes.put("sources", t -> through(as(t).delete("/api/v1/sources/rt-none")));
        probes.put("set-variables", t -> through(as(t).body(Map.of("type", "text", "value", "probe"))
            .put("/api/v1/variables/rt_probe_" + suffix)));
        probes.put("samples", t -> through(as(t).body(Map.of("sources", Map.of())).post("/api/v1/samples")));
        probes.put("write-sql", t -> through(as(t).body(Map.of("assetUrn", "urn:kilnmark:none:x", "type", "custom_sql"))
            .post("/api/v1/quality/rules")));
        // a changed reading setting asks for the password again: sent, so only the grant decides
        probes.put("reading-marked", t -> done(as(t).body(Map.of("settings", Map.of("rows",
            String.valueOf(ROWS.incrementAndGet())), "password", "probe-secret-1")).put("/api/v1/sources/" + marked)));
        probes.put("unmask", t -> as(t).get("/api/v1/me").then().statusCode(200).extract().jsonPath()
            .getBoolean("unmask"));
        probes.put("clear-mark", t -> {
            boolean cleared = through(as(t).body(Map.of("field", "name", "sensitive", false))
                .put("/api/v1/quality/asset/sensitive?urn=" + markedUrn));
            if (cleared) {
                as(Tokens.ADMIN).body(Map.of("field", "name", "sensitive", true))
                    .put("/api/v1/quality/asset/sensitive?urn=" + markedUrn).then().statusCode(204);
            }
            // deleting a source with marks: only the last caller (an admin) may, and then it is gone
            boolean deleted = through(as(t).delete("/api/v1/sources/" + gone));
            assertEquals(cleared, deleted, "clearing a mark and deleting a marked source take the same");
            return cleared;
        });
        probes.put("admin-kinds", t -> through(as(t).body(Map.of("name", "rt-jdbc", "kind", "jdbc", "settings",
            Map.of("url", "jdbc:h2:mem:x"))).post("/api/v1/drafts/test")));
        probes.put("settings", t -> through(as(t).get("/api/v1/mail/settings")));
        probes.put("tokens", t -> through(as(t).get("/api/v1/tokens")));
        probes.put("audit", t -> through(as(t).get("/api/v1/audit?limit=1")));

        assertEquals(RoleTable.LINES.stream().map(RoleTable.Can::id).toList(), List.copyOf(probes.keySet()),
            "one probe for each line, in order");

        // an admin last: deleting the marked source is for admins alone, and leaves nothing to try after
        List<Caller> callers = List.of(new Caller("viewer", AccessRole.VIEWER, false, Tokens.newToken("viewer", false)),
            new Caller("steward", AccessRole.STEWARD, false, Tokens.newToken("steward", false)),
            new Caller("designer", AccessRole.DESIGNER, false, Tokens.newToken("designer", false)),
            new Caller("designer with the grant", AccessRole.DESIGNER, true, Tokens.newToken("designer", true)),
            new Caller("admin", AccessRole.ADMIN, false, Tokens.ADMIN));
        List<String> wrong = new ArrayList<>();
        try {
            for (Caller c : callers) {
                for (RoleTable.Can line : RoleTable.LINES) {
                    boolean said = line.allows(c.role(), c.grant());
                    last = "";
                    boolean done = probes.get(line.id()).apply(c.token());
                    if (said != done) {
                        wrong.add(line.id() + " as a " + c.label() + ": the table says " + (said ? "yes" : "no")
                            + ", the server " + (done ? "allows it" : "refuses it") + " (" + last + ")");
                    }
                }
            }
        } finally {
            admin().delete("/api/v1/variables/rt_probe_" + suffix);
            admin().delete("/api/v1/sources/" + marked);
            admin().delete("/api/v1/sources/" + gone);
        }
        assertEquals(List.of(), wrong);
    }
}

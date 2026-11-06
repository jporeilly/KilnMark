package io.kilnmark.server.api;

import static io.kilnmark.server.Tokens.newToken;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import io.kilnmark.server.Tokens;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;

/**
 * containers/launch/kilnmark-run.ps1 against this test server, as RunScriptTest runs the shell
 * edition: each outcome gives its exit code, and the same request key finds the same run. Runs only
 * when KILNMARK_TEST_POWERSHELL names powershell.exe (or pwsh), since CI's Linux runners have no
 * Windows PowerShell 5.1, the edition's target.
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class RunScriptPowerShellTest {

    static final Path SCRIPT = Path.of("../containers/launch/kilnmark-run.ps1").toAbsolutePath().normalize();

    static String powershell;
    static Path tokenFile;
    static String steward;

    @TestHTTPResource("/")
    URL base;

    /** What a run of the script printed, and how it exited. */
    record Ran(int code, String out) {
    }

    @BeforeAll
    static void findPowerShell() {
        powershell = System.getenv("KILNMARK_TEST_POWERSHELL");
        Assumptions.assumeTrue(powershell != null && !powershell.isBlank(),
            "set KILNMARK_TEST_POWERSHELL to powershell.exe to run these");
    }

    private Ran run(String... args) throws IOException, InterruptedException {
        List<String> command = new ArrayList<>(List.of(powershell, "-NoProfile", "-NonInteractive",
            "-ExecutionPolicy", "Bypass", "-File", SCRIPT.toString()));
        command.addAll(List.of(args));
        ProcessBuilder pb = new ProcessBuilder(command).redirectErrorStream(true);
        pb.environment().put("KILNMARK_URL", base.toString());
        pb.environment().put("KILNMARK_TOKEN_FILE", tokenFile.toString());
        pb.environment().remove("KILNMARK_CA_FILE");
        Process p = pb.start();
        byte[] out = p.getInputStream().readAllBytes();
        assertTrue(p.waitFor(120, TimeUnit.SECONDS), "the script ended");
        return new Ran(p.exitValue(), new String(out, StandardCharsets.UTF_8));
    }

    private static void flow(String designer, String yaml) {
        Tokens.as(designer).contentType(ContentType.JSON).body(Map.of("yaml", yaml)).post("/api/v1/flows").then()
            .statusCode(201);
    }

    @Test
    @Order(1)
    void setUp() throws IOException {
        steward = newToken("steward", false);
        String designer = newToken("designer", false);
        tokenFile = Files.createTempFile("kilnmark-run-token", ".txt");
        Files.writeString(tokenFile, steward + "\r\n");
        flow(designer, """
            kilnmark: flow/v1
            name: rp-ok
            parameters:
              label: { type: text, default: none }
              share: { type: number, default: 0.02 }
              codes: { type: number list, default: [1] }
            nodes:
              - { id: pause, type: wait, for: 1s }
            """);
        flow(designer, """
            kilnmark: flow/v1
            name: rp-fail
            nodes:
              - { id: halt, type: stop, message: Stopped on purpose }
            """);
        flow(designer, """
            kilnmark: flow/v1
            name: rp-long
            nodes:
              - { id: hold, type: wait, for: 10m }
            """);
    }

    @Test
    @Order(2)
    void eachOutcomeHasItsCode() throws Exception {
        assertEquals(5, run().code());
        Ran ok = run("flow", "rp-ok", "--param", "label=north", "--param", "share=0.5", "--param", "codes=[2,3]",
            "--key", "rp key 1");
        assertEquals(0, ok.code(), ok.out());
        assertTrue(ok.out().contains("Status: succeeded") && ok.out().contains("/#/flow-runs/"), ok.out());
        assertTrue(!ok.out().contains(steward), "the token is never printed");
        Ran again = run("flow", "rp-ok", "--key", "rp key 1");
        assertEquals(0, again.code(), again.out());
        assertTrue(again.out().contains("started earlier with this request key"), again.out());
        Ran failed = run("flow", "rp-fail");
        assertEquals(1, failed.code(), failed.out());
        Ran missing = run("flow", "rp-missing");
        assertEquals(2, missing.code(), missing.out());
        Ran late = run("flow", "rp-long", "--timeout", "3", "--cancel-on-timeout");
        assertEquals(3, late.code(), late.out());
        assertTrue(late.out().contains("Cancel: asked"), late.out());
    }
}

package io.kilnmark.server.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/**
 * Both editions of the run script against a stub of Kilnmark's API, for what a real server cannot
 * easily show: a parameter goes exactly as typed, by one rule in both editions (007 as text, a 17-digit
 * number unrounded); an answer that is not Kilnmark's (a sign-in page) is 5, never "failed"; a server
 * that stays busy is given up after a bounded number of tries, reading without waiting after each;
 * the PowerShell edition stops with 5 on a CA file that is not a certificate; and the shell edition,
 * with no mktemp -d, makes a new private folder and never writes into one that is there already.
 * The shell edition needs sh (KILNMARK_TEST_SH on Windows); the PowerShell one KILNMARK_TEST_POWERSHELL.
 */
class RunScriptStubTest {

    static final Path SH_SCRIPT = Path.of("../containers/launch/kilnmark-run.sh").toAbsolutePath().normalize();
    static final Path PS_SCRIPT = Path.of("../containers/launch/kilnmark-run.ps1").toAbsolutePath().normalize();
    static final String TOKEN = "km_stub-token-0123456789abcdefghijklmnopqrstuvwxyzABCD";

    @TempDir
    Path dir;

    HttpServer server;
    /** What the stub does: ok, html or busy. */
    volatile String mode = "ok";
    volatile String body;
    final AtomicInteger reads = new AtomicInteger();
    final AtomicInteger readsWithoutWait = new AtomicInteger();

    record Ran(int code, String out) {
    }

    static String sh() {
        String given = System.getenv("KILNMARK_TEST_SH");
        boolean windows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
        return given != null && !given.isBlank() ? given : windows ? null : "sh";
    }

    static String powershell() {
        String given = System.getenv("KILNMARK_TEST_POWERSHELL");
        return given == null || given.isBlank() ? null : given;
    }

    @BeforeEach
    void startStub() throws IOException {
        server = HttpServer.create(new InetSocketAddress(java.net.InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", this::answer);
        server.start();
    }

    @AfterEach
    void stopStub() {
        server.stop(0);
    }

    private void answer(HttpExchange x) throws IOException {
        boolean text = String.valueOf(x.getRequestHeaders().getFirst("Accept")).startsWith("text/plain");
        String run = text ? "id: 7\nkind: flow\nname: rs\nstatus: %s\nended: %s\npage: /#/flow-runs/7\nmessage: \n"
            : "{\"id\":7,\"flow\":\"rs\",\"status\":\"%s\",\"error\":null}";
        int status;
        String out;
        String type = text ? "text/plain" : "application/json";
        if ("POST".equals(x.getRequestMethod())) {
            body = new String(x.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            if ("html".equals(mode)) {
                status = 200;
                out = "<html><body>Sign in</body></html>";
                type = "text/html";
            } else if ("busy".equals(mode)) {
                status = 202;
                out = run.formatted("running", "false");
            } else {
                status = 200;
                out = run.formatted("succeeded", "true");
            }
        } else {
            reads.incrementAndGet();
            if (x.getRequestURI().getQuery() != null && x.getRequestURI().getQuery().contains("wait=0")) {
                readsWithoutWait.incrementAndGet();
            }
            status = 503;
            out = "{\"error\":\"busy\"}";
            type = "application/json";
            x.getResponseHeaders().add("Retry-After", "0");
        }
        if (!text && "GET".equals(x.getRequestMethod()) && status == 200) {
            out = "{\"run\":" + out + "}";
        }
        byte[] bytes = out.getBytes(StandardCharsets.UTF_8);
        x.getResponseHeaders().add("Content-Type", type);
        x.sendResponseHeaders(status, bytes.length);
        x.getResponseBody().write(bytes);
        x.close();
    }

    private Ran run(List<String> command, Map<String, String> env) throws IOException, InterruptedException {
        Path tokenFile = dir.resolve("token");
        Files.writeString(tokenFile, TOKEN + "\n");
        ProcessBuilder pb = new ProcessBuilder(command).redirectErrorStream(true);
        pb.environment().put("KILNMARK_URL", "http://" + server.getAddress().getAddress().getHostAddress() + ":" + server.getAddress().getPort());
        pb.environment().put("KILNMARK_TOKEN_FILE", tokenFile.toString().replace('\\', '/'));
        pb.environment().remove("KILNMARK_CA_FILE");
        pb.environment().putAll(env);
        Process p = pb.start();
        byte[] out = p.getInputStream().readAllBytes();
        assertTrue(p.waitFor(120, TimeUnit.SECONDS), "the script ended");
        return new Ran(p.exitValue(), new String(out, StandardCharsets.UTF_8));
    }

    private Ran sh(Map<String, String> env, String... args) throws IOException, InterruptedException {
        List<String> command = new ArrayList<>(List.of(sh(), SH_SCRIPT.toString().replace('\\', '/')));
        command.addAll(List.of(args));
        return run(command, env);
    }

    private Ran ps(Map<String, String> env, String... args) throws IOException, InterruptedException {
        List<String> command = new ArrayList<>(List.of(powershell(), "-NoProfile", "-NonInteractive",
            "-ExecutionPolicy", "Bypass", "-File", PS_SCRIPT.toString()));
        command.addAll(List.of(args));
        return run(command, env);
    }

    /** The arguments both editions get, and the body both must send for them. */
    static final String[] VALUES = {"flow", "rs", "--key", "values-1", "--param", "code=007", "--param",
        "big=12345678901234567", "--param", "share=0.5", "--param", "codes=[1, 2]", "--param", "on=true",
        "--param", "words=two words", "--version", "3"};
    static final String SENT = "{\"parameters\":{\"code\":\"007\",\"big\":12345678901234567,\"share\":0.5,"
        + "\"codes\":[1, 2],\"on\":true,\"words\":\"two words\"},\"version\":3}";

    @Test
    void theShellEditionSendsValuesExactlyAsTyped() throws Exception {
        Assumptions.assumeTrue(sh() != null, "no sh here");
        Ran r = sh(Map.of(), VALUES);
        assertEquals(0, r.code(), r.out());
        assertEquals(SENT, body);
    }

    @Test
    void thePowerShellEditionSendsTheSame() throws Exception {
        Assumptions.assumeTrue(powershell() != null, "no PowerShell here");
        Ran r = ps(Map.of(), VALUES);
        assertEquals(0, r.code(), r.out());
        assertEquals(SENT, body);
    }

    @Test
    void aKeyOrValueWithALineBreakIsRefusedWhole() throws Exception {
        Assumptions.assumeTrue(sh() != null, "no sh here");
        Ran key = sh(Map.of(), "flow", "rs", "--key", "first\nsecond");
        assertEquals(5, key.code(), key.out());
        assertTrue(key.out().contains("--key takes"), key.out());
        Ran value = sh(Map.of(), "flow", "rs", "--param", "n=1\n,\"version\":9");
        assertEquals(5, value.code(), value.out());
        assertTrue(value.out().contains("control character"), value.out());
        assertEquals(null, body, "nothing was sent");
    }

    @Test
    void anAnswerThatIsNotKilnmarksIs5() throws Exception {
        mode = "html";
        if (sh() != null) {
            Ran r = sh(Map.of(), "flow", "rs");
            assertEquals(5, r.code(), r.out());
            assertTrue(r.out().contains("not Kilnmark's"), r.out());
        }
        if (powershell() != null) {
            Ran r = ps(Map.of(), "flow", "rs");
            assertEquals(5, r.code(), r.out());
            assertTrue(r.out().contains("not Kilnmark's"), r.out());
        }
    }

    @Test
    void aServerThatStaysBusyIsGivenUp() throws Exception {
        mode = "busy";
        if (sh() != null) {
            Ran r = sh(Map.of(), "flow", "rs");
            assertEquals(5, r.code(), r.out());
            assertTrue(r.out().contains("stayed busy"), r.out());
            assertEquals(60, reads.get(), "60 busy answers in a row");
            assertTrue(readsWithoutWait.get() >= 30, "after each busy answer, a read without waiting");
        }
        reads.set(0);
        readsWithoutWait.set(0);
        if (powershell() != null) {
            Ran r = ps(Map.of(), "flow", "rs");
            assertEquals(5, r.code(), r.out());
            assertTrue(r.out().contains("stayed busy"), r.out());
            assertEquals(60, reads.get(), "60 busy answers in a row");
            assertTrue(readsWithoutWait.get() >= 30, "after each busy answer, a read without waiting");
        }
    }

    @Test
    void aCaFileThatIsNotACertificateIs5() throws Exception {
        Assumptions.assumeTrue(powershell() != null, "no PowerShell here");
        Path notACertificate = Files.writeString(dir.resolve("ca.pem"), "not a certificate\n");
        Ran r = ps(Map.of("KILNMARK_CA_FILE", notACertificate.toString()), "flow", "rs");
        assertEquals(5, r.code(), r.out());
    }

    /** A folder of stand-ins: a mktemp with no -d, and an od that always gives the same "random" bytes. */
    private Path withoutMktemp() throws IOException {
        Path bin = Files.createDirectories(dir.resolve("bin"));
        Files.writeString(bin.resolve("mktemp"), "#!/bin/sh\nexit 1\n");
        Files.writeString(bin.resolve("od"), "#!/bin/sh\necho ' 42 42 42 42 42 42 42 42'\n");
        for (Path f : List.of(bin.resolve("mktemp"), bin.resolve("od"))) {
            try {
                Files.setPosixFilePermissions(f, PosixFilePermissions.fromString("rwxr-xr-x"));
            } catch (UnsupportedOperationException e) {
                // Windows: Git Bash runs a file with a #! line
            }
        }
        return bin;
    }

    private Map<String, String> pathFirst(Path bin, Path tmp) {
        String sep = System.getProperty("path.separator");
        return Map.of("PATH", bin + sep + System.getenv("PATH"), "TMPDIR", tmp.toString().replace('\\', '/'));
    }

    @Test
    void withoutMktempAFolderThatIsThereAlreadyIsNeverUsed() throws Exception {
        Assumptions.assumeTrue(sh() != null, "no sh here");
        Path tmp = Files.createDirectories(dir.resolve("tmp"));
        Path planted = Files.createDirectories(tmp.resolve("kilnmark-run.4242424242424242"));
        Ran r = sh(pathFirst(withoutMktemp(), tmp), "flow", "rs", "--key", "k");
        assertEquals(5, r.code(), r.out());
        try (var left = Files.list(planted)) {
            assertEquals(0, left.count(), "nothing was written into the folder that was there");
        }
        assertFalse(r.out().contains(TOKEN));
    }

    @Test
    void withoutMktempANewPrivateFolderIsMadeAndRemoved() throws Exception {
        Assumptions.assumeTrue(sh() != null, "no sh here");
        Path tmp = Files.createDirectories(dir.resolve("tmp"));
        Ran r = sh(pathFirst(withoutMktemp(), tmp), "flow", "rs", "--key", "k");
        assertEquals(0, r.code(), r.out());
        try (var left = Files.list(tmp)) {
            assertEquals(0, left.count(), "the folder is removed at the end");
        }
    }
}

package io.kilnmark.server.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/**
 * The guided install (ADR 0051, decision 17), in both editions: the answers file (no secrets, every
 * key known, every answer checked and named by its key and line, never its value), install's dry
 * run, {@code configure} (an answer changed later, checked, written to config/.env and
 * config/install.conf; the mode, the project and the folders never), {@code check}'s fixes (each
 * problem with the command that fixes it: Podman's version, WSL 2.7.11, cgroups' pids controller,
 * a busy port, the folders' owners, root or an elevated prompt, a production install's settings),
 * and the Windows install itself, into folders with spaces in their paths, as far as it runs
 * without an administrator.
 *
 * <p>The scripts run for real, with ControlScriptStarterTest's fake engine, which also answers
 * Podman's version, cgroups and memory as check asks them (FAKE_PODMAN_VERSION, FAKE_CGROUPS).
 * Bash runs where there is one (set KILNMARK_TEST_BASH on Windows); Windows PowerShell where
 * KILNMARK_TEST_POWERSHELL names it.
 */
class ControlScriptInstallTest {

    static final Path LAUNCH = ControlScriptStarterTest.LAUNCH;

    /** One edition of the control script. */
    interface Edition {
        /** The script's words to run {@code args}. */
        List<String> command(Path script, List<String> args);

        boolean windows();

        /** A release folder: this edition's script and a compose.yaml naming version 9.9.9. */
        default Path release() throws IOException {
            Path dir = Files.createTempDirectory("kilnmark-release");
            String script = windows() ? "kilnmark.ps1" : "kilnmark.sh";
            Files.copy(LAUNCH.resolve(script), dir.resolve(script));
            Files.writeString(dir.resolve("compose.yaml"), "services:\n  kilnmark:\n    image: ghcr.io/jporeilly/kilnmark-server:9.9.9\n");
            Files.copy(Path.of("../containers/release.env.example"), dir.resolve(".env.example"));
            Files.createDirectories(dir.resolve("containers/keycloak"));
            Files.writeString(dir.resolve("containers/keycloak/setup.sh"), "# setup\n");
            Files.copy(LAUNCH.resolve("install.conf.example"), dir.resolve("install.conf.example"));
            return dir;
        }

        /** The option and value words as this edition spells them. */
        default String option(String linux) {
            if (!windows()) {
                return linux;
            }
            return switch (linux) {
                case "--answers" -> "-Answers";
                case "--dry-run" -> "-DryRun";
                case "--starter" -> "-Starter";
                case "--no-start" -> "-NoStart";
                case "--offline" -> "-Offline";
                case "--skip-verify" -> "-SkipVerify";
                case "--purge" -> "-Purge";
                default -> linux;
            };
        }
    }

    static Edition linux() {
        String bash = System.getenv("KILNMARK_TEST_BASH");
        boolean windows = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win");
        String shell = bash != null && !bash.isBlank() ? bash : windows ? null : "bash";
        Assumptions.assumeTrue(shell != null, "set KILNMARK_TEST_BASH to bash to run this on Windows");
        return new Edition() {
            @Override
            public List<String> command(Path script, List<String> args) {
                List<String> c = new ArrayList<>(List.of(shell, script.toAbsolutePath().toString()));
                c.addAll(args.stream().map(a -> a.replace('\\', '/')).toList());
                return c;
            }

            @Override
            public boolean windows() {
                return false;
            }
        };
    }

    static Edition windowsEdition() {
        String powershell = System.getenv("KILNMARK_TEST_POWERSHELL");
        Assumptions.assumeTrue(powershell != null && !powershell.isBlank(), "set KILNMARK_TEST_POWERSHELL to powershell.exe to run this");
        return new Edition() {
            @Override
            public List<String> command(Path script, List<String> args) {
                List<String> c = new ArrayList<>(List.of(powershell, "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass", "-File",
                    script.toAbsolutePath().toString()));
                c.addAll(args);
                return c;
            }

            @Override
            public boolean windows() {
                return true;
            }
        };
    }

    // ---- the answers file and install's dry run ----

    @Test
    void theLinuxEditionChecksEveryAnswer() throws Exception {
        answersRules(linux());
    }

    @Test
    void theWindowsEditionChecksEveryAnswer() throws Exception {
        answersRules(windowsEdition());
    }

    /** Four free ports, for port, https_port, db_port and keycloak_port. */
    static List<String> fourPorts() throws IOException {
        List<String> out = new ArrayList<>();
        List<ServerSocket> held = new ArrayList<>();
        try {
            while (out.size() < 4) {
                ServerSocket s = new ServerSocket(0);
                held.add(s);
                out.add(String.valueOf(s.getLocalPort()));
            }
        } finally {
            for (ServerSocket s : held) {
                s.close();
            }
        }
        return out;
    }

    /** Answers for a production install with the bundled Keycloak and a first admin, on free ports. */
    static String goodAnswers(List<String> ports) {
        return "mode=production\nidentity=bundled\nemail_domain=bank.example\nhost=localhost\ntls=off\n"
            + "port=" + ports.get(0) + "\nhttps_port=" + ports.get(1) + "\ndb_port=" + ports.get(2) + "\nkeycloak_port=" + ports.get(3) + "\n"
            + "admin_email=ada.lovelace@bank.example\nadmin_first_name=Ada\nadmin_last_name=Lovelace\nproject=kmtest\n";
    }

    static Path answersFile(String text) throws IOException {
        Path f = Files.createTempFile("kilnmark-answers", ".conf");
        Files.writeString(f, text);
        return f;
    }

    /** A wsl.exe that says it is this version, for check (KILNMARK_WSL). */
    static Path fakeWsl(boolean windows, String version) throws IOException {
        Path dir = Files.createTempDirectory("kilnmark-fake-wsl");
        if (windows) {
            Path f = dir.resolve("wsl.cmd");
            Files.writeString(f, "@echo off\r\necho WSL version: " + version + "\r\necho Kernel version: 6.6.87.2-1\r\nexit /b 0\r\n");
            return f;
        }
        Path f = dir.resolve("wsl.sh");
        Files.writeString(f, "#!/bin/bash\necho 'WSL version: " + version + "'\necho 'Kernel version: 6.6.87.2-1'\n");
        try {
            Files.setPosixFilePermissions(f, java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"));
        } catch (UnsupportedOperationException e) {
            // Windows: Git Bash runs it by its first line
        }
        return f;
    }

    /** What every check run sees: WSL 2.7.11 (Linux: as if in WSL), unless {@code more} says otherwise. */
    static Map<String, String> checkEnv(Edition e, String wsl, Map<String, String> more) throws IOException {
        Map<String, String> env = new LinkedHashMap<>();
        env.put("KILNMARK_WSL", fakeWsl(e.windows(), wsl).toString().replace('\\', e.windows() ? '\\' : '/'));
        if (!e.windows()) {
            env.put("KILNMARK_TEST_WSL", "yes");
        }
        env.putAll(more);
        return env;
    }

    static String run(Edition e, Path script, List<String> args, Path root, Path engine, Map<String, String> env, String input)
        throws Exception {
        String before = ControlScriptStarterTest.stdin;
        ControlScriptStarterTest.stdin = input;
        try {
            return ControlScriptStarterTest.run(e.command(script, args), root, engine.toString().replace('\\', e.windows() ? '\\' : '/'),
                env);
        } finally {
            ControlScriptStarterTest.stdin = before;
        }
    }

    private static void answersRules(Edition e) throws Exception {
        Path release = e.release();
        String script = e.windows() ? "kilnmark.ps1" : "kilnmark.sh";
        Path engine = ControlScriptStarterTest.fakeEngine(e.windows(), true);
        Path none = Files.createTempDirectory("kilnmark-none");
        Map<String, String> env = checkEnv(e, "2.7.11.0", Map.of());
        List<String> ports = fourPorts();
        String good = goodAnswers(ports);
        java.util.function.Function<Path, List<String>> dry = f -> List.of("install", e.option("--answers"), f.toString(), e.option("--dry-run"));
        String ok = run(e, release.resolve(script), dry.apply(answersFile(good)), none, engine, env, "");
        assertTrue(ok.contains("[1/2] OK") && ok.contains("[2/2] OK") && ok.contains("Nothing was changed"), ok);
        assertTrue(ok.contains("admin_email=ada.lovelace@bank.example") && ok.contains("project=kmtest"), "the answers it would use: " + ok);
        assertTrue(Pattern.compile("\\[1/2\\] OK \\([0-9]+\\.[0-9] s\\)").matcher(ok).find(), "each step with the time it took: " + ok);

        // the example file says every key, and holds no secret; with free ports it passes as it is
        String example = Files.readString(LAUNCH.resolve("install.conf.example"));
        for (String key : List.of("mode", "identity", "email_domain", "host", "tls", "tls_cert", "tls_key", "port", "https_port", "db_port",
            "keycloak_port", "admin_email", "admin_first_name", "admin_last_name", "oidc_issuer", "oidc_client_id", "oidc_provider_name",
            "oidc_admin_groups", "oidc_designer_groups", "oidc_steward_groups", "oidc_viewer_groups", "oidc_unmask_groups", "project",
            "program_dir", "data_dir", "user", "operator")) {
            assertTrue(Pattern.compile("(?m)^#? ?" + key + "=").matcher(example).find(), "install.conf.example explains " + key);
        }
        assertFalse(Pattern.compile("(?im)^#? ?[a-z_]*(password|secret|token)[a-z_]*=").matcher(example).find(), "no secret in the example");
        String examplePorts = example.replaceAll("(?m)^port=.*$", "port=" + ports.get(0)).replaceAll("(?m)^db_port=.*$", "db_port=" + ports.get(2))
            .replaceAll("(?m)^keycloak_port=.*$", "keycloak_port=" + ports.get(3)).replaceAll("(?m)^# https_port=.*$", "https_port=" + ports.get(1));
        String exampleOut = run(e, release.resolve(script), dry.apply(answersFile(examplePorts)), none, engine, env, "");
        assertTrue(exampleOut.contains("Nothing was changed"), exampleOut);

        // never a secret in an answers file: refused by key and line, the value never shown
        String secret = run(e, release.resolve(script), dry.apply(answersFile(good + "admin_password=hunter2-hunter2\n")), none, engine, env, "");
        assertTrue(secret.contains("line 14: admin_password is a secret, and an answers file holds none") && !secret.contains("hunter2"), secret);
        String token = run(e, release.resolve(script), dry.apply(answersFile(good + "oidc_client_secret=s3cret-s3cret\n")), none, engine, env, "");
        assertTrue(token.contains("oidc_client_secret is a secret") && !token.contains("s3cret"), token);
        // an unknown key, a key twice, a line that is not KEY=VALUE
        String unknown = run(e, release.resolve(script), dry.apply(answersFile("mode=production\nmodes=production\nmode=production\njust words\n")),
            none, engine, env, "");
        assertTrue(unknown.contains("line 2: modes is not an answer install knows") && unknown.contains("line 3: mode is given twice")
            && unknown.contains("line 4 is not KEY=VALUE") && unknown.contains("nothing was changed"), unknown);

        // each answer checked, named by its key and line, never by its value
        String bad = run(e, release.resolve(script), dry.apply(answersFile("mode=Production\nhost=Bad_Host.example\nport=99999\n"
            + "email_domain=Bank.Example\ntls=maybe\nproject=My Project\n")), none, engine, env, "");
        for (String said : List.of("mode (line 1): mode is production or starter-pack", "host (line 2): host is a host name in lower case",
            "port (line 3): port is a port number from 1 to 65535", "email_domain (line 4): email_domain is a domain name in lower case",
            "tls (line 5): tls is off, on", "project (line 6): project is lower-case letters", "[1/2] FAILED")) {
            assertTrue(bad.contains(said), said + ": " + bad);
        }
        assertFalse(bad.contains("Bad_Host") || bad.contains("Bank.Example") || bad.contains("My Project"), "never the value: " + bad);
        assertFalse(bad.contains("[2/2]"), "nothing after the answers: " + bad);
        // answers that do not go together
        String clash = run(e, release.resolve(script), dry.apply(answersFile("mode=starter-pack\nidentity=own\nhost=kilnmark.bank.example\ntls=off\n"
            + "port=" + ports.get(0) + "\nhttps_port=" + ports.get(1) + "\ndb_port=" + ports.get(0) + "\n")), none, engine, env, "");
        assertTrue(clash.contains("a Starter Pack install signs in with the bundled Keycloak only") && clash.contains("db_port is a port another answer uses already")
            && clash.contains("people sign in over HTTPS only"), clash);
        // the first admin: never at kilnmark.com, and at the email domain
        for (String[] admin : new String[][] {{"rita@kilnmark.com", "kilnmark.com addresses are the Starter Pack's example people"},
            {"rita@elsewhere.example", "admin_email is not at email_domain"}, {"Rita@Bank.example", "admin_email is an email address in lower case"}}) {
            String out = run(e, release.resolve(script), dry.apply(answersFile(good.replace("ada.lovelace@bank.example", admin[0]))), none, engine, env, "");
            assertTrue(out.contains("admin_email (line 10): " + admin[1]), admin[0] + ": " + out);
        }
        // a port in use, with one that is free
        try (ServerSocket busy = new ServerSocket()) {
            busy.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
            String used = run(e, release.resolve(script), dry.apply(answersFile(good.replace("port=" + ports.get(0) + "\n",
                "port=" + busy.getLocalPort() + "\n"))), none, engine, env, "");
            assertTrue(used.contains("port: port " + busy.getLocalPort() + " is in use (port ") && used.contains(" is free)"), used);
        }
        // --starter with answers of another mode
        List<String> starter = new ArrayList<>(dry.apply(answersFile(good)));
        starter.add(e.option("--starter"));
        assertTrue(run(e, release.resolve(script), starter, none, engine, env, "").contains("makes a Starter Pack install, and the answers say"));
        // a Starter Pack install: always the bundled Keycloak, and Keycloak's port checked too
        String pack = run(e, release.resolve(script), dry.apply(answersFile(good.replace("mode=production\nidentity=bundled\n", "mode=starter-pack\n"))),
            none, engine, env, "");
        assertTrue(pack.contains("identity=bundled") && pack.contains("port " + ports.get(3) + " is free") && pack.contains("Nothing was changed"), pack);
        // Windows: install runs as the operator, who runs Podman; naming someone else is refused
        if (e.windows()) {
            String other = run(e, release.resolve(script), dry.apply(answersFile(good + "operator=NT AUTHORITY\\SYSTEM\n")), none, engine, env, "");
            assertTrue(other.contains("Install runs as the operator") && !other.contains("[2/2]"), other);
        }
        // no terminal to ask at, and no answers: nothing asked, nothing done
        String asked = run(e, release.resolve(script), List.of("install", e.option("--dry-run")), none, engine, env, "");
        assertTrue(asked.contains(e.windows() ? "There is no console to ask the questions at" : "There is no terminal to ask the questions at"), asked);
    }

    // ---- configure ----

    @Test
    void theLinuxEditionConfiguresAnAnswerLater() throws Exception {
        configureRules(linux());
    }

    @Test
    void theWindowsEditionConfiguresAnAnswerLater() throws Exception {
        configureRules(windowsEdition());
    }

    /** An installed root (program and data in one folder here), production with the bundled Keycloak, on these ports. */
    static Path installed(Edition e, List<String> ports, Map<String, String> settings) throws IOException {
        Path root = ControlScriptStarterTest.installRoot(e.windows());
        Map<String, String> env = new LinkedHashMap<>(Map.of("KILNMARK_MODE", "production", "COMPOSE_PROFILES", "auth"));
        env.put("KILNMARK_PORT", ports.get(0));
        env.put("KILNMARK_HTTPS_PORT", ports.get(1));
        env.put("KILNMARK_DB_PORT", ports.get(2));
        env.put("KILNMARK_KEYCLOAK_PORT", ports.get(3));
        env.putAll(settings);
        ControlScriptStarterTest.writeEnv(root, env);
        String conf = "mode=production\nidentity=bundled\nhost=localhost\ntls=off\nport=" + ports.get(0) + "\nhttps_port=" + ports.get(1)
            + "\ndb_port=" + ports.get(2) + "\nkeycloak_port=" + ports.get(3) + "\nproject=kilnmark\n" + (e.windows() ? "" : "user=kmtest-nobody\n");
        Files.writeString(root.resolve("config/install.conf"), conf);
        Files.copy(Path.of("../containers/release.env.example"), root.resolve("versions/9.9.9/.env.example"));
        return root;
    }

    static Path launcher(Edition e) {
        return LAUNCH.resolve(e.windows() ? "kilnmark.ps1" : "kilnmark.sh");
    }

    private static void configureRules(Edition e) throws Exception {
        Path engine = ControlScriptStarterTest.fakeEngine(e.windows(), true);
        List<String> ports = fourPorts();
        Path root = installed(e, ports, Map.of());
        String envBefore = Files.readString(root.resolve("config/.env"));
        // no answer given: what they are
        String shown = run(e, launcher(e), List.of("configure"), root, engine, Map.of(), "");
        assertTrue(shown.contains("This install's answers") && shown.contains("port=" + ports.get(0)), shown);
        // the mode never changes, nor the project or the folders: each needs a new install
        String mode = run(e, launcher(e), List.of("configure", "mode=starter-pack"), root, engine, Map.of(), "");
        assertTrue(mode.contains("An install's mode never changes (ADR 0051)") && mode.contains("[1/3] FAILED"), mode);
        assertTrue(run(e, launcher(e), List.of("configure", "project=other"), root, engine, Map.of(), "").contains("project is fixed at install"));
        assertTrue(run(e, launcher(e), List.of("configure", "data_dir=" + (e.windows() ? "D:\\Elsewhere" : "/srv/elsewhere")), root, engine, Map.of(), "")
            .contains("data_dir is fixed at install"));
        // never a secret on its command line; never a key install does not know
        String secret = run(e, launcher(e), List.of("configure", "oidc_client_secret=s3cret-s3cret"), root, engine, Map.of(), "");
        assertTrue(secret.contains("oidc_client_secret is a secret") && !secret.contains("s3cret-s3cret"), secret);
        assertTrue(run(e, launcher(e), List.of("configure", "colour=blue"), root, engine, Map.of(), "").contains("colour is not an answer install knows"));
        // an answer checked as at install, never shown
        String bad = run(e, launcher(e), List.of("configure", "host=Not A Host"), root, engine, Map.of(), "");
        assertTrue(bad.contains("host is a host name in lower case") && !bad.contains("Not A Host"), bad);
        assertTrue(run(e, launcher(e), List.of("configure", "tls=on"), root, engine, Map.of(), "").contains("tls_cert"));
        // a port in use is refused, with one that is free
        try (ServerSocket busy = new ServerSocket()) {
            busy.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
            String used = run(e, launcher(e), List.of("configure", "port=" + busy.getLocalPort()), root, engine, Map.of(), "");
            assertTrue(used.contains("port: port " + busy.getLocalPort() + " is in use (port "), used);
        }
        assertEquals(envBefore, Files.readString(root.resolve("config/.env")), "nothing refused changed config/.env");
        // a new port: written to config/.env (the port and the URL that follows it) and install.conf;
        // Kilnmark is not running here, so it takes effect at the next start
        String port = fourPorts().get(0);
        String moved = run(e, launcher(e), List.of("configure", "port=" + port), root, engine, Map.of(), "");
        assertTrue(moved.contains("[3/3] OK") && moved.contains("takes effect at its next start"), moved);
        Map<String, String> after = ControlScriptStarterTest.readEnv(root);
        assertEquals(port, after.get("KILNMARK_PORT"), moved);
        assertEquals("http://localhost:" + port, after.get("KILNMARK_URL"), moved);
        assertTrue(Files.readString(root.resolve("config/install.conf")).contains("port=" + port + "\n"));
        assertEquals(envBefore.lines().filter(l -> l.startsWith("KILNMARK_DB_PASSWORD=")).toList(),
            Files.readString(root.resolve("config/.env")).lines().filter(l -> l.startsWith("KILNMARK_DB_PASSWORD=")).toList(), "secrets kept");
        // the bundled Keycloak's secrets made where there were none, never shown
        assertTrue(after.getOrDefault("KILNMARK_KEYCLOAK_ADMIN_PASSWORD", "").length() >= 40, moved);
        assertFalse(moved.contains(after.get("KILNMARK_KEYCLOAK_ADMIN_PASSWORD")), "a secret was shown");
        // the email domain: nothing to run again
        String domain = run(e, launcher(e), List.of("configure", "email_domain=bank.example"), root, engine, Map.of(), "");
        assertTrue(domain.contains("Nothing to run again") && ControlScriptStarterTest.readEnv(root).get("KILNMARK_EMAIL_DOMAIN").equals("bank.example"),
            domain);
        // the same answer again: nothing changes
        assertTrue(run(e, launcher(e), List.of("configure", "port=" + port), root, engine, Map.of(), "").contains("those are the answers already"));
    }

    // ---- check ----

    @Test
    void theLinuxEditionsCheckSaysHowToFixEachProblem() throws Exception {
        checkRules(linux());
    }

    @Test
    void theWindowsEditionsCheckSaysHowToFixEachProblem() throws Exception {
        checkRules(windowsEdition());
    }

    /** The Fix: line after the first line that says {@code problem}, or empty. */
    static String fixFor(String out, String problem) {
        List<String> lines = out.lines().toList();
        for (int i = 0; i < lines.size() - 1; i++) {
            if (lines.get(i).contains(problem)) {
                String next = lines.get(i + 1).strip();
                return next.startsWith("Fix: ") ? next.substring(5) : "";
            }
        }
        return "";
    }

    private static void checkRules(Edition e) throws Exception {
        Path engine = ControlScriptStarterTest.fakeEngine(e.windows(), true);
        // Kilnmark's port held by something else that is not Kilnmark (it answers 404)
        var other = com.sun.net.httpserver.HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        other.start();
        try {
            List<String> ports = new ArrayList<>(fourPorts());
            ports.set(0, String.valueOf(other.getAddress().getPort()));
            Path root = installed(e, ports, Map.of("KILNMARK_STARTER", "allowed"));
            // Podman too old
            String old = run(e, launcher(e), List.of("check"), root, engine, checkEnv(e, "2.7.11.0", Map.of("FAKE_PODMAN_VERSION", "4.9.3")), "");
            assertTrue(old.contains("Problem: Podman 4.9.3 is too old: Kilnmark needs Podman 5 or later"), old);
            assertTrue(fixFor(old, "Podman 4.9.3 is too old").contains(e.windows() ? "winget upgrade RedHat.Podman-Desktop" : "podman"), old);
            assertTrue(old.contains("OK: WSL 2.7.11"), old);
            // WSL 3.0.x, and crun without the pids controller: WSL 2.7.11, and how to put it in
            String wsl = run(e, launcher(e), List.of("check"), root, engine, checkEnv(e, "3.0.1.0", Map.of("FAKE_CGROUPS", "[cpu memory]")), "");
            assertTrue(wsl.contains("WSL 3.0.1.0 breaks crun's pids controller") && wsl.contains("Podman needs WSL 2.7.11"), wsl);
            assertTrue(fixFor(wsl, "WSL 3.0.1.0 breaks").contains("releases/download/2.7.11/wsl.2.7.11.0.x64.msi"), wsl);
            assertTrue(fixFor(wsl, "Podman cannot use cgroups' pids controller").contains("wsl.2.7.11.0.x64.msi"), wsl);
            // the busy port: configure moves Kilnmark to a free one
            String fix = fixFor(wsl, "Port " + ports.get(0) + " is in use.");
            Matcher m = Pattern.compile("configure port=([0-9]+)$").matcher(fix);
            assertTrue(m.find(), wsl);
            assertTrue(fix.contains(e.windows() ? "kilnmark.cmd configure port=" : "kilnmark.sh configure port="), fix);
            // a production install with the Starter Pack's setting in config/.env
            assertTrue(fixFor(wsl, "KILNMARK_STARTER is set on a production install").contains("delete the line KILNMARK_STARTER"), wsl);
            // no backup yet
            assertTrue(fixFor(wsl, "There is no backup").endsWith(" backup"), wsl);
            assertTrue(wsl.contains("Warning: Kilnmark is not running.") && wsl.contains("People expiry: not set up"), wsl);
            // the folders: as install leaves them, else how to make them so
            if (e.windows()) {
                // a folder you own is not the Administrators' program folder
                assertTrue(fixFor(wsl, "is owned by someone install did not give it to").contains("icacls")
                    && fixFor(wsl, "is owned by someone install did not give it to").contains("/setowner *S-1-5-32-544"), wsl);
                String elevated = run(e, launcher(e), List.of("check"), root, engine, checkEnv(e, "2.7.11.0", Map.of("KILNMARK_TEST_ELEVATED", "yes")), "");
                assertTrue(fixFor(elevated, "check runs elevated here").contains("open a prompt that is not elevated"), elevated);
                // and every day-to-day command refuses to run elevated
                String up = run(e, launcher(e), List.of("up", "-NoBrowser"), root, engine, Map.of("KILNMARK_TEST_ELEVATED", "yes"), "");
                assertTrue(up.contains("from a prompt that is not elevated") && !up.contains("Starting Kilnmark"), up);
            } else {
                assertTrue(fixFor(wsl, "is not owned by the account that runs Kilnmark")
                    .contains("sudo chown -R kmtest-nobody:kmtest-nobody"), wsl);
                assertTrue(fixFor(wsl, "Kilnmark runs as kmtest-nobody").contains("sudo -iu kmtest-nobody"), wsl);
                String asRoot = run(e, launcher(e), List.of("check"), root, engine, checkEnv(e, "2.7.11.0", Map.of("KILNMARK_TEST_UID", "0")), "");
                assertTrue(fixFor(asRoot, "check runs as root here").contains("sudo -iu kmtest-nobody"), asRoot);
            }
            // not installed: the machine's prerequisites only
            String machine = run(e, launcher(e), List.of("check"), Files.createTempDirectory("kilnmark-none"), engine, checkEnv(e, "2.7.11.0", Map.of()), "");
            assertTrue(machine.contains("Checking this machine for a Kilnmark install") && machine.contains("OK: Podman 5.4.2"), machine);
        } finally {
            other.stop(0);
        }
    }

    // ---- the Windows install, as far as it runs without an administrator ----

    /**
     * install into a program folder and a data folder with spaces in their paths: the Administrators'
     * part runs as it is under the tests' marker (never asking UAC), the folders get only the
     * permissions install gives them, config/.env its new secrets and install.conf the answers and no
     * secret; a second install into them is refused; the hourly task quotes the script's path; and
     * uninstall -Purge removes both.
     */
    @Test
    void theWindowsEditionInstallsIntoFoldersWithSpaces() throws Exception {
        Edition e = windowsEdition();
        Path release = e.release();
        Path engine = ControlScriptStarterTest.fakeEngine(true, true);
        Path base = Files.createTempDirectory("kilnmark install");
        Path prog = base.resolve("Program Files").resolve("Kilnmark");
        Path data = base.resolve("ProgramData").resolve("Kilnmark");
        List<String> ports = fourPorts();
        Path answers = answersFile(goodAnswers(ports) + "program_dir=" + prog + "\ndata_dir=" + data + "\n");
        Path images = Files.createTempFile("kilnmark-images", ".tar.gz");
        Map<String, String> env = checkEnv(e, "2.7.11.0", Map.of());
        List<String> install = List.of("install", "-Answers", answers.toString(), "-NoStart", "-Offline", images.toString(), "-SkipVerify");
        String out = run(e, release.resolve("kilnmark.ps1"), install, prog, engine, env, ControlScriptStarterTest.PERSON_PASSWORD);
        assertTrue(out.contains("[3/4] OK") && out.contains("[4/4] OK") && out.contains("is installed: the program in " + prog), out);
        assertFalse(out.contains(ControlScriptStarterTest.PERSON_PASSWORD), "the password was shown");
        assertEquals(data.toString(), Files.readString(prog.resolve("data-folder.txt")).strip());
        assertEquals("9.9.9", Files.readString(prog.resolve("current.txt")).strip());
        assertTrue(Files.isRegularFile(prog.resolve("versions/9.9.9/kilnmark.ps1")) && Files.isRegularFile(prog.resolve("kilnmark.ps1")));
        Map<String, String> settings = ControlScriptStarterTest.readEnv(data);
        assertEquals("production", settings.get("KILNMARK_MODE"));
        assertEquals("http://localhost:" + ports.get(0), settings.get("KILNMARK_URL"));
        assertEquals("kmtest", settings.get("KILNMARK_PROJECT"));
        assertTrue(settings.get("KILNMARK_DB_PASSWORD").length() >= 40 && settings.get("KILNMARK_ADMIN_TOKEN").startsWith("km_"), "new secrets");
        String conf = Files.readString(data.resolve("config/install.conf"));
        assertTrue(conf.contains("mode=production\n") && conf.contains("program_dir=" + prog + "\n"), conf);
        assertFalse(conf.toLowerCase(java.util.Locale.ROOT).matches("(?s).*(password|secret|token)=.*"), "no secret in install.conf: " + conf);
        // the program folder: the Administrators' and SYSTEM's to change, read-only to everyone else;
        // the data folder: yours, SYSTEM's and the Administrators', nobody else's at all
        // (the tests play the administrator, so they may too)
        String me = System.getProperty("user.name");
        for (Path p : List.of(prog, prog.resolve("kilnmark.ps1"), prog.resolve("versions/9.9.9/compose.yaml"))) {
            assertEquals(Set.of(), writers(p, Set.of("SYSTEM", "Administrators", me)), p.toString());
        }
        for (Path p : List.of(data, data.resolve("config/.env"), data.resolve("logs"))) {
            assertEquals(Set.of(), readers(p, Set.of("SYSTEM", "Administrators", me)), p.toString());
        }
        // a fresh install only: never over one that is there
        String again = run(e, release.resolve("kilnmark.ps1"), install, prog, engine, env, ControlScriptStarterTest.PERSON_PASSWORD);
        assertTrue(again.contains("already") && again.contains("FAILED"), again);
        // up sets up the hourly task, the script's path quoted (a space in it)
        try (ControlScriptStarterTest.Api api = ControlScriptStarterTest.Api.start()) {
            Path envFile = data.resolve("config/.env");
            String text = Files.readString(envFile).replaceAll("(?m)^KILNMARK_PORT=.*$", "KILNMARK_PORT=" + api.port())
                .replaceAll("(?m)^KILNMARK_KEYCLOAK_PORT=.*$", "KILNMARK_KEYCLOAK_PORT=" + api.port());
            Files.writeString(envFile, text);
            Map<String, String> upEnv = new LinkedHashMap<>(ControlScriptStarterTest.recordedAsEnv(Map.of("KILNMARK_MODE", "production"), Map.of()));
            upEnv.put("FAKE_TIMER_LOG", base.resolve("schtasks.log").toString());
            upEnv.put("FAKE_UP_LOG", base.resolve("up.log").toString());
            String up = run(e, prog.resolve("kilnmark.ps1"), List.of("up", "-NoBrowser"), prog, engine, upEnv, "");
            assertTrue(up.contains("is ready at") && up.contains("Set up the hourly expiry"), up);
            String asked = Files.readString(base.resolve("schtasks.log"));
            assertTrue(asked.contains("\\\"" + prog.resolve("kilnmark.ps1") + "\\\" people expire -Project kmtest"), asked);
        }
        // uninstall -Purge: the task, the program folder and the data folder go
        Map<String, String> goEnv = new LinkedHashMap<>(env);
        goEnv.put("FAKE_TIMER_LOG", base.resolve("schtasks.log").toString());
        goEnv.put("FAKE_RMI_LOG", base.resolve("rmi.log").toString());
        String gone = run(e, prog.resolve("kilnmark.ps1"), List.of("uninstall", "-Purge"), prog, engine, goEnv, "DELETE");
        assertTrue(gone.contains("[2/2] OK") && gone.contains("Kilnmark is uninstalled, and its data deleted"), gone);
        assertFalse(Files.exists(prog) || Files.exists(data), gone);
    }

    /** Who besides {@code allowed} (names, or the end of one) may change {@code p}. */
    static Set<String> writers(Path p, Set<String> allowed) throws IOException {
        Set<String> out = new java.util.TreeSet<>();
        var view = Files.getFileAttributeView(p, java.nio.file.attribute.AclFileAttributeView.class);
        for (var a : view.getAcl()) {
            if (a.type() != java.nio.file.attribute.AclEntryType.ALLOW || a.flags().contains(java.nio.file.attribute.AclEntryFlag.INHERIT_ONLY)) {
                continue;
            }
            var w = a.permissions();
            if (w.contains(java.nio.file.attribute.AclEntryPermission.WRITE_DATA) || w.contains(java.nio.file.attribute.AclEntryPermission.APPEND_DATA)
                || w.contains(java.nio.file.attribute.AclEntryPermission.WRITE_ACL) || w.contains(java.nio.file.attribute.AclEntryPermission.DELETE)) {
                String name = a.principal().getName();
                if (allowed.stream().noneMatch(name::endsWith)) {
                    out.add(name);
                }
            }
        }
        return out;
    }

    /** Who besides {@code allowed} may do anything at all with {@code p}. */
    static Set<String> readers(Path p, Set<String> allowed) throws IOException {
        Set<String> out = new java.util.TreeSet<>();
        var view = Files.getFileAttributeView(p, java.nio.file.attribute.AclFileAttributeView.class);
        for (var a : view.getAcl()) {
            if (a.type() == java.nio.file.attribute.AclEntryType.ALLOW && allowed.stream().noneMatch(a.principal().getName()::endsWith)) {
                out.add(a.principal().getName());
            }
        }
        return out;
    }

    /** The test file is ASCII-clean and keeps its own words: nothing here names a real secret. */
    @Test
    void theExampleAnswersFileIsPlainText() throws IOException {
        byte[] bytes = Files.readAllBytes(LAUNCH.resolve("install.conf.example"));
        for (int i = 0; i < bytes.length; i++) {
            assertTrue(bytes[i] >= 0 && bytes[i] != '\r', "install.conf.example: plain ASCII with LF line ends, at " + i);
        }
        assertTrue(new String(bytes, StandardCharsets.US_ASCII).contains("This file holds NO secrets"));
    }
}

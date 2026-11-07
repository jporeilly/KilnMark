package io.kilnmark.server.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/**
 * The control script's part in the Starter Pack (ADR 0051), in both editions: before every start it
 * adds the samples' database password when config/.env has none and empties the retired
 * KILNMARK_KEYCLOAK_DEMO_PASSWORD, and refuses a KILNMARK_PRODUCTION that is not true or false (in
 * any case, as the server reads it); {@code starter people} refuses on production (TRUE too),
 * without KILNMARK_STARTER=allowed and without Keycloak, and hands the people's new password to that
 * one keycloak-setup run on its standard input only, showing it on the terminal and never in logs. And the
 * release compose file: a writable files folder for every install, the samples-db one-off, no
 * default for any new secret, and no Keycloak credential for the kilnmark service in any setting.
 *
 * <p>The scripts are run for real, with an engine that cannot start anything: they change
 * config/.env, or refuse, then stop at the engine. Bash runs where there is one (set
 * KILNMARK_TEST_BASH on Windows); Windows PowerShell where KILNMARK_TEST_POWERSHELL names it.
 */
class ControlScriptStarterTest {

    static final Path LAUNCH = Path.of("../containers/launch");
    static final Path COMPOSE = Path.of("../containers/compose.release.yaml");

    // ---- what both editions are made of ----

    @Test
    void theWindowsEditionIsAscii() throws IOException {
        for (String f : List.of("kilnmark.ps1", "kilnmark.cmd")) {
            byte[] bytes = Files.readAllBytes(LAUNCH.resolve(f));
            for (int i = 0; i < bytes.length; i++) {
                assertTrue(bytes[i] >= 0, f + " has a byte beyond ASCII at " + i);
            }
        }
    }

    @Test
    void bothEditionsTurnThePackOnThroughTheApiWithTheTokenOffEveryCommandLine() throws IOException {
        List<String> sh = Files.readAllLines(LAUNCH.resolve("kilnmark.sh"));
        int header = -1;
        for (int i = 0; i < sh.size(); i++) {
            String line = sh.get(i);
            if (line.contains("KILNMARK_ADMIN_TOKEN") && line.contains("Bearer")) {
                header = i;
            }
        }
        assertTrue(header > 0, "kilnmark.sh writes the token into curl's settings");
        assertEquals("header = \"Authorization: Bearer $(setting KILNMARK_ADMIN_TOKEN)\"", sh.get(header).strip());
        assertEquals("cat <<EOF", sh.get(header - 1).strip(), "by a here-document, not a command's arguments");
        String shText = String.join("\n", sh);
        assertTrue(shText.contains("--config \"$starter_work/curl.conf\" -X POST"), "curl reads the token from that file");
        assertTrue(shText.contains("trap '[ -z \"$starter_work\" ] || rm -rf \"$starter_work\"' EXIT")
            && shText.contains("trap 'exit 130' INT") && shText.contains("trap 'exit 143' TERM")
            && shText.contains("trap 'exit 129' HUP"), "the folder goes however the script ends");
        assertTrue(shText.contains("--starter) with_starter=true"), "install --starter");

        String ps = Files.readString(LAUNCH.resolve("kilnmark.ps1"));
        assertTrue(ps.contains("[IO.File]::WriteAllText($configFile, $config"), "the token goes into curl's settings file");
        assertTrue(ps.contains("& curl.exe -k -s -K $configFile -X POST"), "curl.exe reads it from there");
        assertTrue(ps.contains("Protect-Folder $work") && ps.contains("Remove-Item $work -Recurse -Force"),
            "in a folder only this account opens, deleted at once");
        assertTrue(ps.contains("'-Starter' { $script:WithStarter = $true }"), "install -Starter");
        // The token is never an argument of a native command: only of Get-Setting, and in $config.
        Matcher m = Pattern.compile("(?m)^.*KILNMARK_ADMIN_TOKEN.*$").matcher(ps);
        while (m.find()) {
            String line = m.group().strip();
            assertFalse(line.contains("curl.exe") && !line.startsWith("$config ="), "token on curl's command line: " + line);
        }
    }

    @Test
    void composeGivesEveryInstallAWritableFilesFolderAndTheSamplesDatabaseWithNoDefaultSecrets() throws IOException {
        String c = Files.readString(COMPOSE);
        assertTrue(c.contains("      - files:/srv/files:Z\n"), "Kilnmark's own files folder, writable: the files volume");
        assertFalse(c.contains("/srv/files:ro"), "never read-only");
        // a site's own folder only ever read-only, apart from Kilnmark's own
        assertTrue(c.contains("      - ${KILNMARK_FILES_DIR:-share}:/srv/share:ro,Z\n"), "the site's folder, read-only");
        assertEquals(1, c.split("KILNMARK_FILES_DIR:", -1).length - 1, "mounted once, read-only");
        assertTrue(c.contains("KILNMARK_FILES_ROOTS: /srv/files,/srv/share\n"));
        assertTrue(c.contains("  samples-db:\n") && c.contains("entrypoint: [\"/usr/local/bin/kilnmark-samples-db.sh\"]"));
        assertTrue(c.contains("KILNMARK_SAMPLES_DB_PASSWORD: ${KILNMARK_SAMPLES_DB_PASSWORD}\n"), "no default");
        String image = Files.readString(Path.of("../containers/postgres/Containerfile"));
        assertTrue(image.contains("COPY kilnmark-samples-db.sh /usr/local/bin/kilnmark-samples-db.sh"),
            "the database image carries the script samples-db runs");
        assertTrue(Files.readString(Path.of("../containers/server/Containerfile"))
            .contains("chown 1001:1001 /var/lib/kilnmark /srv/files"),
            "the server image makes /srv/files its user's, so a new files volume is writable");
    }

    /**
     * No Keycloak credential reaches the kilnmark container, whatever config/.env says: its service
     * names no Keycloak setting and no password or secret but its own database's and the samples',
     * and takes no env_file that could pass the rest along.
     */
    @Test
    void noKeycloakCredentialReachesKilnmarkInAnyConfiguration() throws IOException {
        String c = Files.readString(COMPOSE);
        String kilnmark = c.substring(c.indexOf("\n  kilnmark:\n"), c.indexOf("\n  keycloak-db:\n"));
        assertFalse(kilnmark.contains("env_file"), "no env_file: only the settings named here reach Kilnmark");
        for (String line : kilnmark.split("\n")) {
            String code = line.strip();
            if (code.startsWith("#") || !code.contains(":") || !code.contains("${")) {
                continue;
            }
            String from = code.substring(code.indexOf("${") + 2).replaceAll("[:}?-].*$", "");
            assertFalse(from.contains("KEYCLOAK"), "a Keycloak setting reaches Kilnmark: " + code);
            assertFalse(from.contains("STARTER"), "a Starter Pack people setting reaches Kilnmark: " + code);
            if (from.contains("PASSWORD") || from.contains("SECRET") || from.endsWith("_TOKEN")) {
                assertTrue(List.of("KILNMARK_DB_PASSWORD", "KILNMARK_ADMIN_TOKEN", "KILNMARK_SAMPLES_DB_PASSWORD",
                    "KILNMARK_OIDC_CLIENT_SECRET").contains(from), "a secret reaches Kilnmark: " + code);
            }
        }
        // the people's password never goes through compose (it is keycloak-setup's standard input), so
        // it is in no container's settings, no podman command line and nothing compose prints
        String setup = c.substring(c.indexOf("\n  keycloak-setup:\n"), c.indexOf("\n  # AI models"));
        assertFalse(c.contains("STARTER_PEOPLE_PASSWORD"), "the people's password is not a compose setting");
        assertEquals(c.indexOf("KILNMARK_STARTER_PEOPLE_ACTION: "), c.lastIndexOf("KILNMARK_STARTER_PEOPLE_ACTION: "),
            "the action, one service's setting");
        assertTrue(setup.contains("KILNMARK_STARTER_PEOPLE_ACTION: ${KILNMARK_STARTER_PEOPLE_ACTION:-}\n"),
            "keycloak-setup's alone");
        for (String f : List.of("../containers/release.env.example", "../containers/keycloak/setup.sh")) {
            assertFalse(Files.readString(Path.of(f)).contains("STARTER_PEOPLE_PASSWORD"), "never a setting: " + f);
        }
        assertTrue(Files.readString(Path.of("../containers/keycloak/setup.sh")).contains("  IFS= read -r PEOPLE_PASSWORD"),
            "setup reads it from its standard input");
        assertFalse(c.contains("KILNMARK_KEYCLOAK_DEMO_PASSWORD"), "the old way is gone");
        assertTrue(setup.contains("KILNMARK_STARTER: ${KILNMARK_STARTER:-}")
            && setup.contains("KILNMARK_PRODUCTION: ${KILNMARK_PRODUCTION:-false}"), "setup refuses on production itself");
    }

    @Test
    void bothEditionsHandThePeoplesPasswordToSetupOnItsStandardInputAndShowItOnTheTerminalOnly() throws IOException {
        String sh = Files.readString(LAUNCH.resolve("kilnmark.sh"));
        assertTrue(sh.contains("  if ! printf '%s\\n' \"$password\" | KILNMARK_STARTER_PEOPLE_ACTION=\"$action\" \\\n"
            + "    \"${compose[@]}\" run --rm -T keycloak-setup 2>&1 | hide_compose_commands; then"),
            "on keycloak-setup's standard input, from printf (a builtin: no command line)");
        assertTrue(sh.contains("echo \"shown only now and kept nowhere, not even in logs/: $password\"")
            && sh.contains("    } > /dev/tty\n  else"), "to the terminal, past logs/");
        String ps = Files.readString(LAUNCH.resolve("kilnmark.ps1")).replace("\r\n", "\n");
        assertTrue(ps.contains("        $password | & $script:Engine @base run --rm -T keycloak-setup 2>&1 |"),
            "on keycloak-setup's standard input");
        for (String text : List.of(sh, ps)) {
            assertFalse(text.contains("STARTER_PEOPLE_PASSWORD"), "never in an environment");
        }
        // Windows PowerShell writes a child's console output into a running transcript: install stops
        // its own around the child that makes the people, and starts it again after
        assertTrue(ps.contains("                Stop-Log\n                & powershell.exe -NoProfile -ExecutionPolicy Bypass "
            + "-File (Join-Path $script:Root 'kilnmark.ps1') starter people\n                Start-Log 'install'\n"),
            "install -Auth -Starter makes the people with no transcript running");
        assertTrue(ps.indexOf("if ([Console]::IsOutputRedirected) {") < ps.indexOf("[Console]::WriteLine('shown only now"),
            "never printed into redirected output");
        assertTrue(ps.contains("[Console]::WriteLine('shown only now and kept nowhere, not even in logs\\: ' + $password)"),
            "to the console, past the transcript");
        for (String text : List.of(sh, ps)) {
            Matcher m = Pattern.compile("(?m)^.*\\$password.*$").matcher(text);
            while (m.find()) {
                String line = m.group();
                assertFalse((line.contains("echo \"") && !line.contains("kept nowhere")) || line.contains("Write-Host"),
                    "the password is printed somewhere logs/ keep: " + line.strip());
            }
        }
    }

    // ---- what up does to config/.env ----

    /** The settings config/.env holds after {@code up} ran with them, by edition. */
    interface Edition {
        Map<String, String> up(Map<String, String> settings) throws Exception;
    }

    private static void starterRules(Edition e) throws Exception {
        // a password for the samples' database is added when there is none, and kept when there is one
        Map<String, String> out = e.up(Map.of());
        assertTrue(out.getOrDefault("KILNMARK_SAMPLES_DB_PASSWORD", "").length() >= 40, out.toString());
        assertEquals("kept-secret-0123456789", e.up(Map.of("KILNMARK_SAMPLES_DB_PASSWORD", "kept-secret-0123456789"))
            .get("KILNMARK_SAMPLES_DB_PASSWORD"));
        // the retired way of making the people: its password is emptied, not kept
        assertEquals("", e.up(Map.of("KILNMARK_KEYCLOAK_DEMO_PASSWORD", "old-demo-0123456789"))
            .getOrDefault("KILNMARK_KEYCLOAK_DEMO_PASSWORD", ""));
    }

    /** What {@code starter people} says, run with the given settings, by edition: its output. */
    interface People {
        String run(Map<String, String> settings) throws Exception;
    }

    /** What a command of the script prints, run with these settings and this outside environment. */
    interface Scripted {
        /** {@code command}: "starter" (starter people) or "down"; {@code logs}: whether logs/ is there. */
        String run(Map<String, String> settings, Map<String, String> outside, String command, boolean logs)
            throws Exception;
    }

    /** The leaked secret the fake engine prints as podman-compose 1.0.x prints a podman command. */
    static final String LEAKED = "leaked-0123456789";

    /**
     * What reaches compose and logs/, through an engine that prints what podman-compose 1.0.x prints
     * (each podman command, settings and all) and the settings it was handed, then fails: the
     * outside environment never decides production or the switch, and no command line reaches logs/.
     */
    private static void composeRules(Scripted s, Path[] root) throws Exception {
        String out = s.run(Map.of("COMPOSE_PROFILES", "auth", "KILNMARK_STARTER", "allowed"),
            Map.of("KILNMARK_PRODUCTION", "from-the-shell", "KILNMARK_STARTER", "from-the-shell"), "starter", true);
        assertTrue(out.contains("keycloak-setup: seen"), out);
        assertFalse(out.contains("from-the-shell"), "the outside environment reached compose: " + out);
        assertFalse(out.contains(LEAKED), "a podman command line was shown: " + out);
        StringBuilder logs = new StringBuilder();
        try (var files = Files.list(root[0].resolve("logs"))) {
            for (Path f : files.toList()) {
                logs.append(Files.readString(f, StandardCharsets.ISO_8859_1));
            }
        }
        assertTrue(logs.toString().contains("keycloak-setup: seen"), "what setup says is kept: " + logs);
        assertFalse(logs.toString().contains(LEAKED), "a podman command line was kept in logs: " + logs);
        // with no logs/ folder nothing is logged, and nothing filtered there: the screen is filtered all the same,
        // for starter people and for a compose call that shows its output only when it fails (down)
        for (String command : List.of("starter", "down")) {
            String bare = s.run(Map.of("COMPOSE_PROFILES", "auth", "KILNMARK_STARTER", "allowed"), Map.of(), command, false);
            assertTrue(bare.contains("keycloak-setup: seen"), command + ": " + bare);
            assertFalse(bare.contains(LEAKED), command + ": a podman command line was shown: " + bare);
        }
        // an export in front, which compose reads too
        assertTrue(s.run(Map.of("COMPOSE_PROFILES", "auth", "KILNMARK_STARTER", "allowed", "export KILNMARK_PRODUCTION",
            "TRUE"), Map.of(), "starter", true).contains("marked production"), "export KILNMARK_PRODUCTION=TRUE is production");
        // the name's case exactly, as compose reads it: kilnmark_production is another setting
        Map<String, String> lower = new LinkedHashMap<>();
        lower.put("COMPOSE_PROFILES", "auth");
        lower.put("KILNMARK_STARTER", "allowed");
        lower.put("KILNMARK_PRODUCTION", "false");
        lower.put("kilnmark_production", "true");
        assertTrue(s.run(lower, Map.of(), "starter", true).contains("keycloak-setup: seen"),
            "KILNMARK_PRODUCTION=false, then kilnmark_production=true: not production, as compose reads it");
    }

    /**
     * keycloak-setup reads all of kcadm's output where it takes a line or looks for one: a reader
     * that stopped early would end kcadm with SIGPIPE, which pipefail turns into "not a member", and
     * an example person would never be removed.
     */
    @Test
    void setupReadsAllOfWhatKcadmPrints() throws Exception {
        String bash = System.getenv("KILNMARK_TEST_BASH");
        boolean windows = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win");
        String shell = bash != null && !bash.isBlank() ? bash : windows ? null : "bash";
        Assumptions.assumeTrue(shell != null, "set KILNMARK_TEST_BASH to bash to run this on Windows");
        String setup = Path.of("../containers/keycloak/setup.sh").toAbsolutePath().toString().replace('\\', '/');
        // as a script file: Windows would mangle the quotes of a -c argument
        Path script = Files.createTempFile("kilnmark-setup-readers", ".sh");
        Files.writeString(script, "set -o pipefail\nf='" + setup + "'\n"
            + "eval \"$(grep '^one() ' \"$f\")\"\neval \"$(grep '^in_group() ' \"$f\")\"\n"
            + "REALM=x\nkc() { seq 1 300000; }\n"
            + "seq 1 300000 | one > /dev/null || exit 3\n"
            + "[ \"$(printf 'a\\nb\\n' | one)\" = a ] || exit 4\n"
            + "in_group someone 1 || exit 5\n! in_group someone x || exit 6\n");
        Process p = new ProcessBuilder(shell, script.toString().replace('\\', '/')).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(p.waitFor(60, TimeUnit.SECONDS));
        assertEquals(0, p.exitValue(), out);
    }

    private static void peopleRules(People p) throws Exception {
        assertTrue(p.run(Map.of("KILNMARK_STARTER", "allowed")).contains("need the bundled Keycloak"), "no Keycloak");
        assertTrue(p.run(Map.of("COMPOSE_PROFILES", "auth")).contains("Set KILNMARK_STARTER=allowed"), "not allowed");
        // production as the server reads it: true in any case
        for (String v : List.of("true", "TRUE", "True")) {
            assertTrue(p.run(Map.of("COMPOSE_PROFILES", "auth", "KILNMARK_STARTER", "allowed", "KILNMARK_PRODUCTION", v))
                .contains("marked production"), "production: " + v);
        }
        // a value the server refuses to start with is refused here too, never taken as not production
        for (String v : List.of("1", "yes", "on")) {
            String out = p.run(Map.of("COMPOSE_PROFILES", "auth", "KILNMARK_STARTER", "allowed", "KILNMARK_PRODUCTION", v));
            assertTrue(out.contains("KILNMARK_PRODUCTION in config") && out.contains("is true or false"), v + ": " + out);
            assertFalse(out.contains("does not work"), v + ": it stopped before the engine");
        }
        // allowed, with Keycloak, not production (false in any case, or unset): it goes on, to the engine
        for (String v : List.of("", "false", "FALSE")) {
            assertTrue(p.run(Map.of("COMPOSE_PROFILES", "auth", "KILNMARK_STARTER", "allowed", "KILNMARK_PRODUCTION", v))
                .contains("does not work"), "allowed: it would run keycloak-setup (" + v + ")");
        }
    }

    @Test
    void theLinuxEditionKeepsTheStarterSettingsRight() throws Exception {
        String bash = System.getenv("KILNMARK_TEST_BASH");
        boolean windows = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win");
        String shell = bash != null && !bash.isBlank() ? bash : windows ? null : "bash";
        Assumptions.assumeTrue(shell != null, "set KILNMARK_TEST_BASH to bash to run this on Windows");
        starterRules(settings -> {
            Path root = installRoot(false);
            writeEnv(root, settings);
            start(List.of(shell, LAUNCH.resolve("kilnmark.sh").toAbsolutePath().toString(), "up", "--no-browser"),
                root, "false");
            return readEnv(root);
        });
        peopleRules(settings -> {
            Path root = installRoot(false);
            writeEnv(root, settings);
            return run(List.of(shell, LAUNCH.resolve("kilnmark.sh").toAbsolutePath().toString(), "starter", "people"),
                root, "false");
        });
        Path engine = fakeEngine(false);
        Path[] last = new Path[1];
        composeRules((settings, outside, command, logs) -> {
            Path root = installRoot(false);
            if (logs) {
                last[0] = root;
            } else {
                Files.delete(root.resolve("logs"));
            }
            writeEnv(root, settings);
            List<String> c = new ArrayList<>(List.of(shell, LAUNCH.resolve("kilnmark.sh").toAbsolutePath().toString()));
            c.addAll(command.equals("down") ? List.of("down") : List.of("starter", "people"));
            return run(c, root, engine.toString().replace('\\', '/'), outside);
        }, last);
        Path works = fakeEngine(false, true);
        redirectedRules((settings, outside, command, logs) -> {
            Path root = installRoot(false);
            writeEnv(root, settings);
            return run(List.of(shell, LAUNCH.resolve("kilnmark.sh").toAbsolutePath().toString(), "starter", "people"),
                root, works.toString().replace('\\', '/'), outside);
        });
    }

    @Test
    void theWindowsEditionKeepsTheStarterSettingsRight() throws Exception {
        String powershell = System.getenv("KILNMARK_TEST_POWERSHELL");
        Assumptions.assumeTrue(powershell != null && !powershell.isBlank(),
            "set KILNMARK_TEST_POWERSHELL to powershell.exe to run this");
        starterRules(settings -> {
            Path root = installRoot(true);
            writeEnv(root, settings);
            start(List.of(powershell, "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass", "-File",
                LAUNCH.resolve("kilnmark.ps1").toAbsolutePath().toString(), "up", "-NoBrowser"), root, "where.exe");
            return readEnv(root);
        });
        peopleRules(settings -> {
            Path root = installRoot(true);
            writeEnv(root, settings);
            return run(List.of(powershell, "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass", "-File",
                LAUNCH.resolve("kilnmark.ps1").toAbsolutePath().toString(), "starter", "people"), root, "where.exe");
        });
        Path engine = fakeEngine(true);
        Path[] last = new Path[1];
        composeRules((settings, outside, command, logs) -> {
            Path root = installRoot(true);
            if (logs) {
                last[0] = root;
            } else {
                Files.delete(root.resolve("logs"));
            }
            writeEnv(root, settings);
            List<String> c = new ArrayList<>(List.of(powershell, "-NoProfile", "-NonInteractive", "-ExecutionPolicy",
                "Bypass", "-File", LAUNCH.resolve("kilnmark.ps1").toAbsolutePath().toString()));
            c.addAll(command.equals("down") ? List.of("down") : List.of("starter", "people"));
            return run(c, root, engine.toString(), outside);
        }, last);
        Path works = fakeEngine(true, true);
        redirectedRules((settings, outside, command, logs) -> {
            Path root = installRoot(true);
            writeEnv(root, settings);
            return run(List.of(powershell, "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass", "-File",
                LAUNCH.resolve("kilnmark.ps1").toAbsolutePath().toString(), "starter", "people"), root, works.toString(),
                outside);
        });
    }

    /**
     * An engine that says compose works, then for anything else prints a podman command line with a
     * secret on it (to its error output, as podman-compose 1.0.x does), and the Starter Pack settings
     * compose was handed, as keycloak-setup's line, and fails.
     */
    private static Path fakeEngine(boolean windows) throws IOException {
        return fakeEngine(windows, false);
    }

    /** As {@link #fakeEngine(boolean)}; {@code ok}: every command but compose version also succeeds. */
    private static Path fakeEngine(boolean windows, boolean ok) throws IOException {
        Path dir = Files.createTempDirectory("kilnmark-fake-engine");
        String exit = ok ? "0" : "1";
        String name = ok ? "engine-ok" : "engine";
        if (windows) {
            Path f = dir.resolve(name + ".cmd");
            Files.writeString(f, "@echo off\r\n"
                + "if \"%1 %2\"==\"compose version\" exit /b 0\r\n"
                + "echo podman run --name kilnmark_x -e KC_ADMIN_PASSWORD=" + LEAKED + " quay.io/keycloak/keycloak 1>&2\r\n"
                + "echo ['podman', 'run', '-e', 'KILNMARK_OIDC_CLIENT_SECRET=" + LEAKED + "'] 1>&2\r\n"
                + "echo keycloak-setup: seen [%KILNMARK_PRODUCTION%] [%KILNMARK_STARTER%]\r\n"
                + "exit /b " + exit + "\r\n");
            return f;
        }
        Path f = dir.resolve(name + ".sh");
        Files.writeString(f, "#!/bin/bash\n"
            + "if [ \"$1 $2\" = \"compose version\" ]; then exit 0; fi\n"
            + "echo \"podman run --name kilnmark_x -e KC_ADMIN_PASSWORD=" + LEAKED + " quay.io/keycloak/keycloak\" >&2\n"
            + "echo \"['podman', 'run', '-e', 'KILNMARK_OIDC_CLIENT_SECRET=" + LEAKED + "']\" >&2\n"
            + "echo \"keycloak-setup: seen [${KILNMARK_PRODUCTION-}] [${KILNMARK_STARTER-}]\"\n"
            + "exit " + exit + "\n");
        try {
            Files.setPosixFilePermissions(f, java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"));
        } catch (UnsupportedOperationException e) {
            // Windows: Git Bash runs it by its first line
        }
        return f;
    }

    /** An install root with one version in it, as install leaves it; Windows marks it in current.txt. */
    private static Path installRoot(boolean windows) throws IOException {
        Path root = Files.createTempDirectory("kilnmark-starter-test");
        Path v = Files.createDirectories(root.resolve("versions/9.9.9"));
        Files.writeString(v.resolve("compose.yaml"), "services:\n  kilnmark:\n    image: ghcr.io/jporeilly/kilnmark-server:9.9.9\n");
        Files.createDirectories(root.resolve("config"));
        Files.createDirectories(root.resolve("logs"));
        if (windows) {
            Files.writeString(root.resolve("current.txt"), "9.9.9\n");
        } else {
            Files.createDirectories(root.resolve("current"));
            Files.writeString(root.resolve("current/compose.yaml"), Files.readString(v.resolve("compose.yaml")));
        }
        return root;
    }

    private static void writeEnv(Path root, Map<String, String> settings) throws IOException {
        StringBuilder b = new StringBuilder("KILNMARK_DB_PASSWORD=db-0123456789\nKILNMARK_ADMIN_TOKEN=km_x\n");
        settings.forEach((k, v) -> b.append(k).append('=').append(v).append('\n'));
        Files.writeString(root.resolve("config/.env"), b.toString());
    }

    private static Map<String, String> readEnv(Path root) throws IOException {
        Map<String, String> out = new LinkedHashMap<>();
        for (String line : Files.readAllLines(root.resolve("config/.env"), StandardCharsets.UTF_8)) {
            int eq = line.indexOf('=');
            if (eq > 0 && !line.startsWith("#")) {
                out.put(line.substring(0, eq).strip(), line.substring(eq + 1).strip());
            }
        }
        return out;
    }

    /** Runs up with an engine that cannot start anything: it stops there, after config/.env is settled. */
    private static void start(List<String> command, Path root, String engine) throws Exception {
        String out = run(command, root, engine);
        assertTrue(out.contains("compose") && out.contains("does not work"), out);
    }

    /**
     * starter people with an engine that does what it is asked, its output redirected (as here, or
     * to a file): the people are made, and their password is not printed, in either edition.
     */
    private static void redirectedRules(Scripted ok) throws Exception {
        String out = ok.run(Map.of("COMPOSE_PROFILES", "auth", "KILNMARK_STARTER", "allowed"), Map.of(), "starter", true);
        assertTrue(out.contains("keycloak-setup: seen"), out);
        assertFalse(out.contains("kept nowhere"), "the password was printed into redirected output: " + out);
        assertTrue(out.contains("no console") || out.contains("no terminal"), out);
    }

    /** Runs a command of the script with an engine that cannot start anything; it must fail. Its output. */
    private static String run(List<String> command, Path root, String engine) throws Exception {
        return run(command, root, engine, Map.of());
    }

    /** As {@link #run(List, Path, String)}, with these in the script's own environment too. */
    private static String run(List<String> command, Path root, String engine, Map<String, String> outside)
        throws Exception {
        ProcessBuilder pb = new ProcessBuilder(new ArrayList<>(command)).redirectErrorStream(true);
        pb.environment().put("KILNMARK_ROOT", root.toString());
        pb.environment().put("KILNMARK_ENGINE", engine);
        pb.environment().remove("KILNMARK_PROJECT");
        pb.environment().putAll(outside);
        Process p = pb.start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(p.waitFor(60, TimeUnit.SECONDS), "it stopped");
        assertTrue(engine.contains("engine-ok") || p.exitValue() != 0, "it cannot go on with no engine: " + out);
        return out;
    }
}

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
 * KILNMARK_KEYCLOAK_DEMO_PASSWORD; {@code starter people} refuses on production, without
 * KILNMARK_STARTER=allowed and without Keycloak, and hands the people's new password to that one
 * keycloak-setup run in its environment only, showing it on the terminal and never in logs. And the
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
        assertTrue(c.contains("- ${KILNMARK_FILES_DIR:-files}:/srv/files:Z"), "a writable files folder, the files volume by default");
        assertFalse(c.contains("/srv/files:ro"), "never read-only");
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
        // the people's password goes to keycloak-setup only, from the control script's own environment
        String setup = c.substring(c.indexOf("\n  keycloak-setup:\n"), c.indexOf("\n  # AI models"));
        assertTrue(setup.contains("KILNMARK_STARTER_PEOPLE_PASSWORD: ${KILNMARK_STARTER_PEOPLE_PASSWORD}\n"), "no default");
        assertTrue(c.indexOf("KILNMARK_STARTER_PEOPLE_PASSWORD") == c.indexOf("      KILNMARK_STARTER_PEOPLE_PASSWORD: ")
            && c.indexOf("KILNMARK_STARTER_PEOPLE_PASSWORD") > c.indexOf("\n  keycloak-setup:\n"), "keycloak-setup only");
        for (String f : List.of("../containers/release.env.example")) {
            assertFalse(Files.readString(Path.of(f)).contains("KILNMARK_STARTER_PEOPLE_PASSWORD"), "never a setting in .env");
        }
        assertFalse(c.contains("KILNMARK_KEYCLOAK_DEMO_PASSWORD"), "the old way is gone");
        assertTrue(setup.contains("KILNMARK_STARTER: ${KILNMARK_STARTER:-}")
            && setup.contains("KILNMARK_PRODUCTION: ${KILNMARK_PRODUCTION:-false}"), "setup refuses on production itself");
    }

    @Test
    void bothEditionsHandThePeoplesPasswordToSetupInItsEnvironmentAndShowItOnTheTerminalOnly() throws IOException {
        String sh = Files.readString(LAUNCH.resolve("kilnmark.sh"));
        assertTrue(sh.contains("KILNMARK_STARTER_PEOPLE_ACTION=\"$action\" KILNMARK_STARTER_PEOPLE_PASSWORD=\"$password\" \\\n"
            + "    \"${compose[@]}\" run --rm -T keycloak-setup"), "in compose's environment, never an argument");
        assertTrue(sh.contains("echo \"shown only now and kept nowhere, not even in logs/: $password\"")
            && sh.contains("    } > /dev/tty\n  else"), "to the terminal, past logs/");
        String ps = Files.readString(LAUNCH.resolve("kilnmark.ps1")).replace("\r\n", "\n");
        assertTrue(ps.contains("$env:KILNMARK_STARTER_PEOPLE_PASSWORD = $password")
            && ps.contains("run --rm -T keycloak-setup")
            && ps.contains("Remove-Item Env:KILNMARK_STARTER_PEOPLE_PASSWORD"), "in the environment of that one run");
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

    private static void peopleRules(People p) throws Exception {
        assertTrue(p.run(Map.of("KILNMARK_STARTER", "allowed")).contains("need the bundled Keycloak"), "no Keycloak");
        assertTrue(p.run(Map.of("COMPOSE_PROFILES", "auth")).contains("Set KILNMARK_STARTER=allowed"), "not allowed");
        assertTrue(p.run(Map.of("COMPOSE_PROFILES", "auth", "KILNMARK_STARTER", "allowed", "KILNMARK_PRODUCTION", "true"))
            .contains("marked production"), "production");
        // allowed, with Keycloak, not production: it goes on, to the engine
        assertTrue(p.run(Map.of("COMPOSE_PROFILES", "auth", "KILNMARK_STARTER", "allowed")).contains("does not work"),
            "allowed: it would run keycloak-setup");
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

    /** Runs a command of the script with an engine that cannot start anything; it must fail. Its output. */
    private static String run(List<String> command, Path root, String engine) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(new ArrayList<>(command)).redirectErrorStream(true);
        pb.environment().put("KILNMARK_ROOT", root.toString());
        pb.environment().put("KILNMARK_ENGINE", engine);
        pb.environment().remove("KILNMARK_PROJECT");
        Process p = pb.start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(p.waitFor(60, TimeUnit.SECONDS), "it stopped");
        assertTrue(p.exitValue() != 0, "it cannot go on with no engine: " + out);
        return out;
    }
}

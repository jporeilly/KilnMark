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
 * adds the samples' database password when config/.env has none, sets the Keycloak service account's
 * secret only while KILNMARK_STARTER=allowed with Keycloak on and the install not marked
 * production, and empties it otherwise, so that secret reaches Kilnmark only then. And the release
 * compose file: a writable files folder for every install, the samples-db one-off, and no default
 * for any of the new secrets.
 *
 * <p>The scripts are run for real, with an engine that cannot start anything: {@code up} changes
 * config/.env, then stops at the engine. Bash runs where there is one (set KILNMARK_TEST_BASH on
 * Windows); Windows PowerShell where KILNMARK_TEST_POWERSHELL names it.
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
        assertTrue(shText.contains("--config \"$work/curl.conf\" -X POST"), "curl reads the token from that file");
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
        assertTrue(c.contains("KILNMARK_STARTER_KEYCLOAK_CLIENT_SECRET: ${KILNMARK_KEYCLOAK_STARTER_SECRET}\n"), "no default");
        // Keycloak's admin password is passed to keycloak and keycloak-setup only, never to the kilnmark service
        String kilnmark = c.substring(c.indexOf("\n  kilnmark:\n"), c.indexOf("\n  keycloak-db:\n"));
        assertFalse(kilnmark.contains("KEYCLOAK_ADMIN"), "the kilnmark service gets no Keycloak admin setting");
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

        // allowed, with Keycloak on: the service account's secret is set, and kept
        out = e.up(Map.of("KILNMARK_STARTER", "allowed", "COMPOSE_PROFILES", "auth"));
        assertTrue(out.getOrDefault("KILNMARK_KEYCLOAK_STARTER_SECRET", "").length() >= 40, out.toString());
        assertEquals("kept-0123456789012345678901234567890123", e.up(Map.of("KILNMARK_STARTER", "allowed", "COMPOSE_PROFILES",
            "auth", "KILNMARK_KEYCLOAK_STARTER_SECRET", "kept-0123456789012345678901234567890123"))
            .get("KILNMARK_KEYCLOAK_STARTER_SECRET"));

        // not allowed, without Keycloak, or on production: emptied, so it never reaches Kilnmark
        String old = "old-0123456789012345678901234567890123456";
        for (Map<String, String> s : List.of(Map.of("COMPOSE_PROFILES", "auth"),
            Map.of("KILNMARK_STARTER", "allowed"),
            Map.of("KILNMARK_STARTER", "yes", "COMPOSE_PROFILES", "auth"),
            Map.of("KILNMARK_STARTER", "allowed", "COMPOSE_PROFILES", "auth", "KILNMARK_PRODUCTION", "true"))) {
            Map<String, String> with = new LinkedHashMap<>(s);
            with.put("KILNMARK_KEYCLOAK_STARTER_SECRET", old);
            assertEquals("", e.up(with).getOrDefault("KILNMARK_KEYCLOAK_STARTER_SECRET", ""), "emptied with " + s);
        }
        assertFalse(e.up(Map.of("KILNMARK_STARTER", "allowed", "COMPOSE_PROFILES", "auth", "KILNMARK_PRODUCTION", "true"))
            .containsKey("KILNMARK_KEYCLOAK_STARTER_SECRET"), "never made on production");
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
    private static Process start(List<String> command, Path root, String engine) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(new ArrayList<>(command)).redirectErrorStream(true);
        pb.environment().put("KILNMARK_ROOT", root.toString());
        pb.environment().put("KILNMARK_ENGINE", engine);
        pb.environment().remove("KILNMARK_PROJECT");
        Process p = pb.start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(p.waitFor(60, TimeUnit.SECONDS), "up stopped at the engine");
        assertTrue(p.exitValue() != 0, "up cannot start with no engine: " + out);
        assertTrue(out.contains("compose") && out.contains("does not work"), out);
        return p;
    }
}

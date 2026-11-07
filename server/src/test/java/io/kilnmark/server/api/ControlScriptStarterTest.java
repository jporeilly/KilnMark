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
 * The control script's part in the two deployment modes and the Starter Pack (ADR 0051), in both
 * editions: before every start it takes KILNMARK_MODE as production or starter-pack only, picks it
 * for an install from before there were modes (starter-pack where KILNMARK_KEYCLOAK_DEMO_PASSWORD
 * shows it has had the example people), refuses production while that retired setting is there,
 * adds the samples' database password on a Starter Pack install only, refuses a KILNMARK_FILES_DIR
 * that is not a full path and warns about an earlier version's share at /srv/files;
 * {@code starter people} refuses on production, without KILNMARK_STARTER=allowed and without
 * Keycloak, and hands the password typed (or piped in) to that one keycloak-setup run on its
 * standard input, never showing it. And the release compose file: a writable files folder for every
 * install, the samples-db one-off on a Starter Pack install only, no default for any new secret,
 * and no Keycloak credential for the kilnmark service in any setting.
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
    void composeGivesEveryInstallAWritableFilesFolderAndTheSamplesDatabaseOnlyInStarterPackMode() throws IOException {
        String c = Files.readString(COMPOSE);
        assertTrue(c.contains("      - files:/srv/files:Z\n"), "Kilnmark's own files folder, writable: the files volume");
        assertFalse(c.contains("/srv/files:ro"), "never read-only");
        // a site's own folder only ever read-only, apart from Kilnmark's own
        assertTrue(c.contains("      - ${KILNMARK_FILES_DIR:-share}:/srv/share:ro,Z\n"), "the site's folder, read-only");
        assertEquals(1, c.split("KILNMARK_FILES_DIR:", -1).length - 1, "mounted once, read-only");
        assertTrue(c.contains("KILNMARK_FILES_ROOTS: /srv/files,/srv/share\n"));
        assertTrue(c.contains("  samples-db:\n    image: ghcr.io/jporeilly/kilnmark-postgres:@VERSION@\n"
            + "    profiles: [\"starter-pack\"]\n"), "the samples' database only on a Starter Pack install");
        assertTrue(c.contains("entrypoint: [\"/usr/local/bin/kilnmark-samples-db.sh\"]"));
        assertTrue(c.contains("KILNMARK_SAMPLES_DB_PASSWORD: ${KILNMARK_SAMPLES_DB_PASSWORD}\n"), "no default");
        assertTrue(c.contains("KILNMARK_MODE: ${KILNMARK_MODE:?"), "the server is given the mode, and has no default");
        String image = Files.readString(Path.of("../containers/postgres/Containerfile"));
        assertTrue(image.contains("COPY kilnmark-samples-db.sh /usr/local/bin/kilnmark-samples-db.sh"),
            "the database image carries the script samples-db runs");
        assertTrue(Files.readString(Path.of("../containers/server/Containerfile"))
            .contains("chown 1001:1001 /var/lib/kilnmark /srv/files"),
            "the server image makes /srv/files its user's, so a new files volume is writable");
        // the developer stack as the release: the share read-only at /srv/share
        String dev = Files.readString(Path.of("../compose.yaml"));
        assertTrue(dev.contains(":/srv/share:ro,Z") && !dev.contains(":/srv/files:ro"), "the dev share at /srv/share");
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
        String script = Files.readString(Path.of("../containers/keycloak/setup.sh"));
        assertTrue(script.contains("  IFS= read -r p || true") && script.contains("PEOPLE_PASSWORD=\"$(read_password ")
            && script.contains("PERSON_PASSWORD=\"$(read_password "), "setup reads them from its standard input");
        assertFalse(script.contains("KILNMARK_PERSON_PASSWORD"), "a person's password is never a setting either");
        assertFalse(c.contains("KILNMARK_KEYCLOAK_DEMO_PASSWORD"), "the old way is gone");
        assertFalse(Files.readString(Path.of("../containers/release.env.example")).contains("KILNMARK_KEYCLOAK_DEMO_PASSWORD="),
            "and not offered in .env.example");
        assertTrue(setup.contains("KILNMARK_STARTER: ${KILNMARK_STARTER:-}")
            && setup.contains("KILNMARK_MODE: ${KILNMARK_MODE:-}"), "setup knows the mode itself");
        // The one-offs' secrets never go in their environment, which podman-compose puts on podman's
        // command line: they read them from config/.env, mounted read-only.
        String samples = c.substring(c.indexOf("\n  samples-db:\n"), c.indexOf("\n  kilnmark:\n"));
        for (String[] one : new String[][] {{"keycloak-setup", setup}, {"samples-db", samples}}) {
            assertTrue(one[1].contains("- ${KILNMARK_ENV_FILE:-../../config/.env}:/run/kilnmark/env:ro,z"), one[0]);
            for (String secret : List.of("PASSWORD}", "SECRET}", "PASSWORD:?", "SECRET:?", "PASSWORD:-", "TOKEN}")) {
                assertFalse(one[1].contains(secret), one[0] + " has a secret in its environment: " + secret);
            }
        }
        String samplesScript = Files.readString(Path.of("../containers/postgres/kilnmark-samples-db.sh"));
        assertTrue(script.contains("KC_ADMIN_PASSWORD=\"$(setting_from_file KILNMARK_KEYCLOAK_ADMIN_PASSWORD)\"")
            && script.contains("KILNMARK_OIDC_CLIENT_SECRET=\"$(setting_from_file KILNMARK_OIDC_CLIENT_SECRET)\""));
        assertTrue(samplesScript.contains("PGPASSWORD=$(setting_from_file KILNMARK_DB_PASSWORD)")
            && samplesScript.contains("KILNMARK_SAMPLES_DB_PASSWORD=$(setting_from_file KILNMARK_SAMPLES_DB_PASSWORD)"));
    }

    /** The example people's password as the tests type it, on the scripts' standard input. */
    static final String PEOPLE_PASSWORD = "typed-people-password-0123";

    @Test
    void thePeoplesPasswordIsTypedOrPipedInAndNeverShown() throws IOException {
        String sh = Files.readString(LAUNCH.resolve("kilnmark.sh"));
        assertTrue(sh.contains("  if ! out=\"$(printf '%s\\n' \"$people_password\" | KILNMARK_STARTER_PEOPLE_ACTION=\"$action\" \\\n"
            + "    \"${compose[@]}\" run --rm -T keycloak-setup 2>&1 | hide_compose_commands)\"; then"),
            "on keycloak-setup's standard input, from printf (a builtin: no command line)");
        assertTrue(sh.contains("read -r -s -p \"A password for the example people"), "typed with the echo off");
        String ps = Files.readString(LAUNCH.resolve("kilnmark.ps1")).replace("\r\n", "\n");
        assertTrue(ps.contains("$out = @($password | & $script:Engine @base run --rm -T keycloak-setup 2>&1 |"),
            "on keycloak-setup's standard input");
        assertTrue(ps.contains("Read-Host -AsSecureString 'A password for the example people"), "typed, not shown");
        // a person's password the same way, beside the email, names and groups compose hands setup
        assertTrue(sh.contains("  run_people_setup add < <(printf '%s\\n' \"$person_password\") || ok=false")
            && sh.contains("run_people_setup import < <(printf '%s\\n' \"$person_password\"; cat -- \"$file\") || ok=false"),
            "on keycloak-setup's standard input, from printf (a builtin) and the file");
        assertTrue(sh.contains("read -r -s -p \"A temporary password for them")
            && sh.contains("read -r -s -p \"A temporary password for $1"), "typed with the echo off");
        assertTrue(ps.contains("$out = @($Lines | & $script:Engine @base run --rm -T keycloak-setup 2>&1 |"),
            "on keycloak-setup's standard input");
        assertTrue(ps.contains("$a = Read-Host -AsSecureString $Prompt"), "typed, not shown");
        for (String text : List.of(sh, ps)) {
            assertFalse(text.contains("STARTER_PEOPLE_PASSWORD") || text.contains("PERSON_PASSWORD"),
                "never in an environment");
            assertFalse(text.contains("[Console]::WriteLine") || text.contains("/dev/tty"), "never written past the logs");
            Matcher m = Pattern.compile("(?m)^.*(\\$(people_|person_)?password\\b|\\$Person\\.Password\\b).*$").matcher(text);
            while (m.find()) {
                String line = m.group().strip();
                assertFalse(line.matches(".*\\b(echo|Write-Host|Write-Output|Out-Host)\\b.*")
                    || (line.contains("printf") && !line.contains("| KILNMARK_STARTER_PEOPLE_ACTION")
                        && !line.contains("< <(printf")),
                    "the password is printed: " + line);
            }
        }
        // install no longer makes the people: they take a password the person running it types
        assertFalse(sh.contains("\"$root/kilnmark.sh\" starter people"), "install runs no people step");
        assertFalse(ps.contains("kilnmark.ps1') starter people"), "install runs no people step");
    }

    // ---- what up does to config/.env ----

    /** What {@code up} printed and the settings config/.env holds after it ran with them, by edition. */
    record Up(String out, Map<String, String> env) {
    }

    interface Edition {
        /** {@code older}: an earlier version's compose.yaml beside, as found on an upgrade. */
        Up up(Map<String, String> settings, String older) throws Exception;
    }

    private static void modeRules(Edition e) throws Exception {
        // a Starter Pack install: a password for the samples' database is added, and kept when there is one
        Up up = e.up(Map.of("KILNMARK_MODE", "starter-pack", "COMPOSE_PROFILES", "auth"), null);
        assertTrue(up.env().getOrDefault("KILNMARK_SAMPLES_DB_PASSWORD", "").length() >= 40, up.out());
        assertEquals("kept-secret-0123456789", e.up(Map.of("KILNMARK_MODE", "starter-pack", "COMPOSE_PROFILES", "auth",
            "KILNMARK_SAMPLES_DB_PASSWORD", "kept-secret-0123456789"), null).env().get("KILNMARK_SAMPLES_DB_PASSWORD"));
        // a Starter Pack install never starts without the bundled Keycloak, where its example people are
        for (String profiles : List.of("", "ai", "authx", "ai,auth-x")) {
            String out = e.up(Map.of("KILNMARK_MODE", "starter-pack", "COMPOSE_PROFILES", profiles), null).out();
            assertTrue(out.contains("needs the bundled Keycloak") && !out.contains("does not work"), profiles + ": " + out);
        }
        assertFalse(e.up(Map.of("KILNMARK_MODE", "starter-pack", "COMPOSE_PROFILES", "ai, auth"), null).out()
            .contains("needs the bundled Keycloak"), "auth among others");
        // production starts with or without it
        assertFalse(e.up(Map.of("KILNMARK_MODE", "production", "COMPOSE_PROFILES", ""), null).out()
            .contains("needs the bundled Keycloak"));
        // a production install has no samples' database
        assertEquals("", e.up(Map.of("KILNMARK_MODE", "production"), null).env()
            .getOrDefault("KILNMARK_SAMPLES_DB_PASSWORD", ""));
        // only the two modes, exactly: anything else stops before the engine
        for (String v : List.of("Production", "PRODUCTION", "starter_pack", "true", "staging")) {
            String out = e.up(Map.of("KILNMARK_MODE", v), null).out();
            assertTrue(out.contains("KILNMARK_MODE in config") && !out.contains("does not work"), v + ": " + out);
        }

        // An install from before there were modes (0.1.1 or earlier, a preview) is never upgraded: no
        // mode is chosen for it, and it does not start; whatever else its settings hold
        for (Map<String, String> old : List.<Map<String, String>>of(Map.of("KILNMARK_MODE", ""),
            Map.of("KILNMARK_MODE", "", "KILNMARK_KEYCLOAK_DEMO_PASSWORD", "old-demo-0123456789", "COMPOSE_PROFILES", "auth"))) {
            Up refused = e.up(old, null);
            assertTrue(refused.out().contains("predates deployment modes") && !refused.out().contains("does not work"),
                refused.out());
            assertEquals("", refused.env().getOrDefault("KILNMARK_MODE", ""), "no mode is chosen for it");
        }
        // the activation window, 1 to 720 hours
        for (String hours : List.of("0", "721", "72h", "-1", "1e2")) {
            String out = e.up(Map.of("KILNMARK_MODE", "production", "KILNMARK_PEOPLE_ACTIVATION_HOURS", hours), null).out();
            assertTrue(out.contains("KILNMARK_PEOPLE_ACTIVATION_HOURS in config") && !out.contains("does not work"), hours + ": " + out);
        }
        assertFalse(e.up(Map.of("KILNMARK_MODE", "production", "KILNMARK_PEOPLE_ACTIVATION_HOURS", "720"), null).out()
            .contains("KILNMARK_PEOPLE_ACTIVATION_HOURS in config"));

        // a folder of your own by its full path only: a bare name would mount a volume of Kilnmark's own
        for (String bare : List.of("keys", "pgdata", "share/x")) {
            String out = e.up(Map.of("KILNMARK_MODE", "production", "KILNMARK_FILES_DIR", bare), null).out();
            assertTrue(out.contains("KILNMARK_FILES_DIR in config") && !out.contains("does not work"), bare + ": " + out);
        }
        // upgrading: an earlier version's compose.yaml mounted a share of your own at /srv/files
        assertTrue(e.up(Map.of("KILNMARK_MODE", "production"), "      - /data/share:/srv/files:ro,Z\n").out()
            .contains("an earlier version mounted a folder of your own at /srv/files"));
    }

    /** What {@code starter people} says, run with the given settings, by edition: its output. */
    interface People {
        String run(Map<String, String> settings) throws Exception;
    }

    private static void peopleRules(People p) throws Exception {
        assertTrue(p.run(Map.of("KILNMARK_MODE", "starter-pack", "KILNMARK_STARTER", "allowed"))
            .contains("needs the bundled Keycloak"), "no Keycloak");
        assertTrue(p.run(Map.of("KILNMARK_MODE", "production", "COMPOSE_PROFILES", "auth", "KILNMARK_STARTER", "allowed"))
            .contains("This is a production install"), "never on a production install");
        assertTrue(p.run(Map.of("KILNMARK_MODE", "starter-pack", "COMPOSE_PROFILES", "auth"))
            .contains("Set KILNMARK_STARTER=allowed"), "not allowed");
        // a Starter Pack install, allowed, with Keycloak: on to the engine, to read the recorded mode
        String allowed = p.run(Map.of("KILNMARK_MODE", "starter-pack", "COMPOSE_PROFILES", "auth", "KILNMARK_STARTER", "allowed"));
        assertTrue(allowed.contains("does not work") || allowed.contains("own record does not say"), allowed);
        // too short a password stops it first
        stdin = "short";
        try {
            String out = p.run(Map.of("KILNMARK_MODE", "starter-pack", "COMPOSE_PROFILES", "auth", "KILNMARK_STARTER", "allowed"));
            assertTrue(out.contains("12 characters or more") && !out.contains("does not work"), out);
        } finally {
            stdin = PEOPLE_PASSWORD;
        }
    }

    /** What a command of the script prints, run with these settings and this outside environment. */
    interface Scripted {
        /** {@code command}: "starter" (starter people) or "down"; {@code logs}: whether logs/ is there. */
        String run(Map<String, String> settings, Map<String, String> outside, String command, boolean logs)
            throws Exception;
    }

    /** The leaked secret the fake engine prints as podman-compose 1.0.x prints a podman command. */
    static final String LEAKED = "leaked-0123456789";

    static final Map<String, String> PEOPLE_ALLOWED = Map.of("KILNMARK_MODE", "starter-pack", "COMPOSE_PROFILES", "auth",
        "KILNMARK_STARTER", "allowed");

    /**
     * What reaches compose and logs/, through an engine that prints what podman-compose 1.0.x prints
     * (each podman command, settings and all) and the settings it was handed, then fails: the outside
     * environment never decides the mode or the switch, no command line reaches the screen or logs/,
     * and the typed password is never shown.
     */
    private static void composeRules(Scripted s, Path[] root) throws Exception {
        String out = s.run(PEOPLE_ALLOWED, Map.of("KILNMARK_MODE", "from-the-shell", "KILNMARK_STARTER", "from-the-shell"),
            "starter", true);
        assertTrue(out.contains("keycloak-setup: seen"), out);
        assertFalse(out.contains("from-the-shell"), "the outside environment reached compose: " + out);
        assertFalse(out.contains(LEAKED), "a podman command line was shown: " + out);
        assertFalse(out.contains(PEOPLE_PASSWORD), "the password was shown: " + out);
        StringBuilder logs = new StringBuilder();
        try (var files = Files.list(root[0].resolve("logs"))) {
            for (Path f : files.toList()) {
                logs.append(Files.readString(f, StandardCharsets.ISO_8859_1));
            }
        }
        assertTrue(logs.toString().contains("keycloak-setup: seen"), "what setup says is kept: " + logs);
        assertFalse(logs.toString().contains(LEAKED), "a podman command line was kept in logs: " + logs);
        assertFalse(logs.toString().contains(PEOPLE_PASSWORD), "the password was kept in logs: " + logs);
        // with no logs/ folder the screen is filtered all the same, for starter people and for a
        // compose call that shows its output only when it fails (down)
        for (String command : List.of("starter", "down")) {
            String bare = s.run(PEOPLE_ALLOWED, Map.of(), command, false);
            assertTrue(bare.contains("keycloak-setup: seen"), command + ": " + bare);
            assertFalse(bare.contains(LEAKED), command + ": a podman command line was shown: " + bare);
        }
        // an export in front, which compose reads too
        assertTrue(s.run(Map.of("COMPOSE_PROFILES", "auth", "KILNMARK_STARTER", "allowed", "export KILNMARK_MODE",
            "production"), Map.of(), "starter", true).contains("This is a production install"),
            "export KILNMARK_MODE=production is production");
        // the name's case exactly, as compose reads it: kilnmark_mode is another setting
        Map<String, String> lower = new LinkedHashMap<>(PEOPLE_ALLOWED);
        lower.put("kilnmark_mode", "production");
        assertTrue(s.run(lower, Map.of(), "starter", true).contains("keycloak-setup: seen"),
            "KILNMARK_MODE=starter-pack, then kilnmark_mode=production: still starter-pack, as compose reads it");
    }

    /**
     * starter people with an engine that does what it is asked: keycloak-setup is handed the typed
     * password, the people are made, and the password is never shown, in either edition.
     */
    private static void madeRules(Scripted ok) throws Exception {
        String out = ok.run(PEOPLE_ALLOWED, Map.of(), "starter", true);
        assertTrue(out.contains("keycloak-setup: got a password"), out);
        assertTrue(out.contains("with the password you gave"), out);
        assertFalse(out.contains(PEOPLE_PASSWORD), "the password was shown: " + out);
        // what Kilnmark recorded at its first start decides, not config/.env alone
        for (String recorded : List.of("production", "")) {
            String refused = ok.run(PEOPLE_ALLOWED, Map.of("FAKE_RECORDED_MODE", recorded.isEmpty() ? " " : recorded),
                "starter", true);
            assertTrue(refused.contains("own record does not say this is a Starter Pack install")
                && !refused.contains("keycloak-setup: got a password"), refused);
        }
    }

    /** A person's temporary password as the tests type it. */
    static final String PERSON_PASSWORD = "typed-person-password-0123";

    /** What a people command printed, what logs/ kept, and what it sent Kilnmark's API, by edition. */
    record Admin(String out, String logs, List<String> posted) {
    }

    interface AdminRun {
        /**
         * {@code args}: the people command's words; {@code input}: the lines on its standard input;
         * {@code ok}: whether keycloak-setup works.
         */
        Admin run(Map<String, String> settings, List<String> args, String input, boolean ok) throws Exception;
    }

    static final Map<String, String> WITH_KEYCLOAK = Map.of("KILNMARK_MODE", "production", "COMPOSE_PROFILES", "auth");
    static final List<String> ADD = List.of("people", "add");
    static final String EXAMPLE_CSV = Path.of("../containers/keycloak/people.example.csv").toAbsolutePath().normalize()
        .toString();

    /**
     * People of your own, in either mode, with the bundled Keycloak only: the email (in lower case),
     * names and groups go to one run of keycloak-setup, the password on its standard input only,
     * never shown or kept in logs/; what setup did goes to Kilnmark's audit log, never with the
     * password; an example address, or one at kilnmark.com, stops it before the engine, as do a short
     * password, a missing name or group, and a one-run setting in .env.
     */
    private static void adminRules(AdminRun a) throws Exception {
        String typed = "Rita@Bank.example\nRita\nOkoro\nkilnmark-admins\n" + PERSON_PASSWORD;
        for (String mode : List.of("production", "starter-pack")) {
            Admin made = a.run(Map.of("KILNMARK_MODE", mode, "COMPOSE_PROFILES", "auth"), ADD, typed, true);
            assertTrue(made.out().contains("keycloak-setup: person [add] [rita@bank.example] [Rita] [Okoro] [kilnmark-admins]"),
                made.out());
            assertTrue(made.out().contains("keycloak-setup: got a password"), made.out());
            assertTrue(made.out().contains("signs in to Kilnmark with that password"), made.out());
            assertFalse(made.out().contains(PERSON_PASSWORD) || made.logs().contains(PERSON_PASSWORD), "the password was shown");
            assertFalse(made.logs().isEmpty(), "logs/ has the run");
            assertEquals(List.of("{\"via\":\"add\",\"made\":[{\"person\":\"rita@bank.example\",\"groups\":\"kilnmark-admins\"}],"
                + "\"kept\":[],\"refused\":0}"), made.posted(), mode);
        }
        // someone with that email already: left as they are, and recorded so
        Admin kept = a.run(WITH_KEYCLOAK, ADD, "here.rita@bank.example\nRita\nOkoro\nkilnmark-viewers\n" + PERSON_PASSWORD, true);
        assertTrue(kept.out().contains("left as they are: nothing was changed"), kept.out());
        assertEquals(List.of("{\"via\":\"add\",\"made\":[],\"kept\":[\"here.rita@bank.example\"],\"refused\":0}"),
            kept.posted());
        // no email: nobody is made, and nothing reaches the engine
        Admin none = a.run(WITH_KEYCLOAK, ADD, "", true);
        assertTrue(none.out().contains("Nobody was made") && !none.out().contains("keycloak-setup:"), none.out());
        // refused before the engine: the example people's addresses, kilnmark.com, not an address
        for (String email : List.of("vera.lane@kilnmark.com", "ALEX.MORGAN@KILNMARK.COM", "rita@kilnmark.com",
            "rita@eu.kilnmark.com", "rita", "rita@bank")) {
            Admin refused = a.run(WITH_KEYCLOAK, ADD, email + "\nRita\nOkoro\nkilnmark-viewers\n" + PERSON_PASSWORD, true);
            assertTrue(refused.out().contains("Nobody was made") && !refused.out().contains("keycloak-setup:"),
                email + ": " + refused.out());
            assertTrue(refused.posted().isEmpty(), email);
        }
        assertTrue(a.run(WITH_KEYCLOAK, ADD, "vera.lane@kilnmark.com\nVera\nLane\nkilnmark-viewers\n" + PERSON_PASSWORD, true)
            .out().contains("kilnmark.com addresses are the Starter Pack's example people"));
        for (String[] bad : new String[][] {{"rita@bank.example\nRita\nOkoro\nkilnmark-viewers\nshort", "12 characters or more"},
            {"rita@bank.example\n\nOkoro\nkilnmark-viewers\n" + PERSON_PASSWORD, "a first and a last name"},
            {"rita@bank.example\nRita\nOkoro\n ; \n" + PERSON_PASSWORD, "at least one of Kilnmark's groups"},
            {"rita@bank.example\nRita\nOkoro\nkilnmark-viewers\ntab\tinside-0123456789", "control character"}}) {
            Admin refused = a.run(WITH_KEYCLOAK, ADD, bad[0], true);
            assertTrue(refused.out().contains(bad[1]) && !refused.out().contains("keycloak-setup:"), refused.out());
        }
        // only with the bundled Keycloak: an outside identity provider's people are added there
        for (List<String> args : List.of(ADD, List.of("people", "import", EXAMPLE_CSV), List.of("people", "list"))) {
            Admin outside = a.run(Map.of("KILNMARK_MODE", "production"), args, typed, true);
            assertTrue(outside.out().contains("add people in your organisation's own identity provider")
                && !outside.out().contains("keycloak-setup:"), outside.out());
        }
        // a one-run setting in .env would reach every start: refused
        for (String key : List.of("KILNMARK_PEOPLE_ACTION", "KILNMARK_PERSON_EMAIL", "KILNMARK_PERSON_GROUPS",
            "KILNMARK_STARTER_PEOPLE_ACTION")) {
            Map<String, String> withIt = new LinkedHashMap<>(WITH_KEYCLOAK);
            withIt.put(key, "add");
            Admin refused = a.run(withIt, ADD, typed, true);
            assertTrue(refused.out().contains(key + " is for one run of the control script only")
                && !refused.out().contains("keycloak-setup:"), refused.out());
        }
        // Kilnmark must answer first, so nobody is made without a record (port 9: nothing listens)
        for (List<String> args : List.of(ADD, List.of("people", "import", EXAMPLE_CSV), List.of("people", "activate", "rita@bank.example"))) {
            Admin silent = a.run(Map.of("KILNMARK_MODE", "production", "COMPOSE_PROFILES", "auth", "KILNMARK_PORT", "9"), args,
                typed, true);
            assertTrue(silent.out().contains("Kilnmark does not answer") && !silent.out().contains("keycloak-setup:"),
                silent.out());
        }
        // setup fails: said, nothing recorded, the password still never shown
        Admin failed = a.run(WITH_KEYCLOAK, ADD, typed, false);
        assertTrue(failed.out().contains("keycloak-setup stopped"), failed.out());
        assertFalse(failed.out().contains(PERSON_PASSWORD), failed.out());
        assertTrue(failed.posted().isEmpty());

        // import on production: nobody is given a password (each waits, disabled, for activate)
        Admin imported = a.run(WITH_KEYCLOAK, List.of("people", "import", EXAMPLE_CSV), "", true);
        assertTrue(imported.out().contains("keycloak-setup: person [import] [] [] [] []"), imported.out());
        assertTrue(imported.out().contains("people import: made 1") && !imported.out().contains(PERSON_PASSWORD), imported.out());
        assertTrue(imported.out().contains("people activate EMAIL"), imported.out());
        assertEquals(List.of("{\"via\":\"import\",\"made\":[{\"person\":\"priya.shah@example.com\",\"groups\":"
            + "\"kilnmark-designers;kilnmark-sensitive-data\"}],\"kept\":[],\"refused\":0}"), imported.posted());
        // on a Starter Pack install the class shares one temporary password, never shown
        Map<String, String> starter = Map.of("KILNMARK_MODE", "starter-pack", "COMPOSE_PROFILES", "auth");
        Admin classImport = a.run(starter, List.of("people", "import", EXAMPLE_CSV), PERSON_PASSWORD, true);
        assertTrue(classImport.out().contains("keycloak-setup: got a password"), classImport.out());
        assertFalse(classImport.out().contains(PERSON_PASSWORD) || classImport.logs().contains(PERSON_PASSWORD),
            "the password was shown");
        assertTrue(a.run(starter, List.of("people", "import", EXAMPLE_CSV), "short", true).out()
            .contains("12 characters or more"));
        // an import setup refused is recorded too, with how many lines it refused
        Admin refusedFile = a.run(WITH_KEYCLOAK, List.of("people", "import", EXAMPLE_CSV), "", false);
        assertTrue(refusedFile.out().contains("keycloak-setup stopped"), refusedFile.out());
        assertEquals(List.of("{\"via\":\"import\",\"made\":[],\"kept\":[],\"refused\":2}"), refusedFile.posted());
        assertTrue(a.run(WITH_KEYCLOAK, List.of("people", "import", EXAMPLE_CSV + ".missing"), "", true).out()
            .contains("Say which file"));
        // activate: one person, their own temporary password, never shown; recorded
        Admin activated = a.run(WITH_KEYCLOAK, List.of("people", "activate", "Rita@Bank.example"), PERSON_PASSWORD, true);
        assertTrue(activated.out().contains("keycloak-setup: person [activate] [rita@bank.example]"), activated.out());
        assertTrue(activated.out().contains("keycloak-setup: got a password"), activated.out());
        assertFalse(activated.out().contains(PERSON_PASSWORD) || activated.logs().contains(PERSON_PASSWORD),
            "the password was shown");
        assertEquals(List.of("{\"via\":\"activate\",\"activated\":[\"rita@bank.example\"]}"), activated.posted());
        Admin notActivated = a.run(WITH_KEYCLOAK, List.of("people", "activate", "rita@bank.example"), PERSON_PASSWORD, false);
        assertTrue(notActivated.out().contains("Nobody was activated") && notActivated.posted().isEmpty(), notActivated.out());
        for (String[] bad : new String[][] {{"rita", PERSON_PASSWORD, "not an email address"},
            {"rita@bank.example", "short", "12 characters or more"},
            {"rita@bank.example", "tab\tinside-0123456789", "control character"}}) {
            Admin refused = a.run(WITH_KEYCLOAK, List.of("people", "activate", bad[0]), bad[1], true);
            assertTrue(refused.out().contains(bad[2]) && !refused.out().contains("keycloak-setup:"), refused.out());
        }
        // list: who is there, and who is waiting; anyone setup disabled for an unused password is recorded
        Admin list = a.run(WITH_KEYCLOAK, List.of("people", "list"), "", true);
        assertTrue(list.out().contains("rita@bank.example groups=kilnmark-admins status=active must-choose-password=yes")
            && list.out().contains("people: 1"), list.out());
        assertEquals(List.of("{\"via\":\"expire\",\"disabled\":[\"old@bank.example\"]}"), list.posted());
    }

    /**
     * Kilnmark's API as the scripts reach it, on a port of its own: each body posted to
     * /api/v1/auth/people, answered 204.
     */
    record Api(com.sun.net.httpserver.HttpServer server, List<String> posted) implements AutoCloseable {
        static Api start() throws IOException {
            List<String> posted = java.util.Collections.synchronizedList(new ArrayList<>());
            var server = com.sun.net.httpserver.HttpServer.create(
                new java.net.InetSocketAddress(java.net.InetAddress.getLoopbackAddress(), 0), 0);
            server.createContext("/api/v1/audit", x -> {
                x.sendResponseHeaders(200, -1);
                x.close();
            });
            server.createContext("/api/v1/auth/people", x -> {
                posted.add(new String(x.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                x.sendResponseHeaders(204, -1);
                x.close();
            });
            server.start();
            return new Api(server, posted);
        }

        int port() {
            return server.getAddress().getPort();
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }

    /** An install root with these settings, Kilnmark's API at the test's server. */
    private static Path adminRoot(boolean windows, Map<String, String> settings, Api api) throws IOException {
        Path root = installRoot(windows);
        Map<String, String> all = new LinkedHashMap<>(settings);
        all.putIfAbsent("KILNMARK_PORT", String.valueOf(api.port()));
        writeEnv(root, all);
        return root;
    }

    private static String logsOf(Path root) throws IOException {
        StringBuilder logs = new StringBuilder();
        try (var files = Files.list(root.resolve("logs"))) {
            for (Path f : files.toList()) {
                logs.append(Files.readString(f, StandardCharsets.ISO_8859_1));
            }
        }
        return logs.toString();
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

    @Test
    void theLinuxEditionKeepsTheStarterSettingsRight() throws Exception {
        String bash = System.getenv("KILNMARK_TEST_BASH");
        boolean windows = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win");
        String shell = bash != null && !bash.isBlank() ? bash : windows ? null : "bash";
        Assumptions.assumeTrue(shell != null, "set KILNMARK_TEST_BASH to bash to run this on Windows");
        String script = LAUNCH.resolve("kilnmark.sh").toAbsolutePath().toString();
        modeRules((settings, older) -> {
            Path root = installRoot(false);
            older(root, older);
            writeEnv(root, settings);
            return new Up(run(List.of(shell, script, "up", "--no-browser"), root, "false"), readEnv(root));
        });
        peopleRules(settings -> {
            Path root = installRoot(false);
            writeEnv(root, settings);
            return run(List.of(shell, script, "starter", "people"), root, "false");
        });
        Path engine = fakeEngine(false, false);
        Path[] last = new Path[1];
        composeRules((settings, outside, command, logs) -> {
            Path root = installRoot(false);
            if (logs) {
                last[0] = root;
            } else {
                Files.delete(root.resolve("logs"));
            }
            writeEnv(root, settings);
            List<String> c = new ArrayList<>(List.of(shell, script));
            c.addAll(command.equals("down") ? List.of("down") : List.of("starter", "people"));
            return run(c, root, engine.toString().replace('\\', '/'), outside);
        }, last);
        Path works = fakeEngine(false, true);
        madeRules((settings, outside, command, logs) -> {
            Path root = installRoot(false);
            writeEnv(root, settings);
            return run(List.of(shell, script, "starter", "people"), root, works.toString().replace('\\', '/'), outside);
        });
        try (Api api = Api.start()) {
            adminRules((settings, args, input, ok) -> {
                Path root = adminRoot(false, settings, api);
                api.posted().clear();
                List<String> c = new ArrayList<>(List.of(shell, script));
                c.addAll(args.stream().map(w -> w.replace('\\', '/')).toList());
                String out = withInput(input, () -> run(c, root, (ok ? works : engine).toString().replace('\\', '/')));
                return new Admin(out, logsOf(root), List.copyOf(api.posted()));
            });
        }
    }

    @Test
    void theWindowsEditionKeepsTheStarterSettingsRight() throws Exception {
        String powershell = System.getenv("KILNMARK_TEST_POWERSHELL");
        Assumptions.assumeTrue(powershell != null && !powershell.isBlank(),
            "set KILNMARK_TEST_POWERSHELL to powershell.exe to run this");
        List<String> ps = List.of(powershell, "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass", "-File",
            LAUNCH.resolve("kilnmark.ps1").toAbsolutePath().toString());
        modeRules((settings, older) -> {
            Path root = installRoot(true);
            older(root, older);
            writeEnv(root, settings);
            List<String> c = new ArrayList<>(ps);
            c.addAll(List.of("up", "-NoBrowser"));
            return new Up(run(c, root, "where.exe"), readEnv(root));
        });
        peopleRules(settings -> {
            Path root = installRoot(true);
            writeEnv(root, settings);
            List<String> c = new ArrayList<>(ps);
            c.addAll(List.of("starter", "people"));
            return run(c, root, "where.exe");
        });
        Path engine = fakeEngine(true, false);
        Path[] last = new Path[1];
        composeRules((settings, outside, command, logs) -> {
            Path root = installRoot(true);
            if (logs) {
                last[0] = root;
            } else {
                Files.delete(root.resolve("logs"));
            }
            writeEnv(root, settings);
            List<String> c = new ArrayList<>(ps);
            c.addAll(command.equals("down") ? List.of("down") : List.of("starter", "people"));
            return run(c, root, engine.toString(), outside);
        }, last);
        Path works = fakeEngine(true, true);
        madeRules((settings, outside, command, logs) -> {
            Path root = installRoot(true);
            writeEnv(root, settings);
            List<String> c = new ArrayList<>(ps);
            c.addAll(List.of("starter", "people"));
            return run(c, root, works.toString(), outside);
        });
        try (Api api = Api.start()) {
            adminRules((settings, args, input, ok) -> {
                Path root = adminRoot(true, settings, api);
                api.posted().clear();
                List<String> c = new ArrayList<>(ps);
                c.addAll(args);
                String out = withInput(input, () -> run(c, root, (ok ? works : engine).toString()));
                return new Admin(out, logsOf(root), List.copyOf(api.posted()));
            });
        }
    }

    interface Call {
        String call() throws Exception;
    }

    /** Runs {@code c} with these lines on the scripts' standard input in place of the people's password. */
    private static String withInput(String input, Call c) throws Exception {
        String before = stdin;
        stdin = input;
        try {
            return c.call();
        } finally {
            stdin = before;
        }
    }

    /**
     * An engine that says compose works, then for anything else prints a podman command line with a
     * secret on it (to its error output, as podman-compose 1.0.x does, joined and as a Python list),
     * whether keycloak-setup was handed a password on its input, and the Starter Pack settings compose
     * was handed, as keycloak-setup's lines; it fails unless {@code ok}.
     */
    private static Path fakeEngine(boolean windows, boolean ok) throws IOException {
        Path dir = Files.createTempDirectory("kilnmark-fake-engine");
        String exit = ok ? "0" : "1";
        String name = ok ? "engine-ok" : "engine";
        if (windows) {
            Path f = dir.resolve(name + ".cmd");
            Files.writeString(f, "@echo off\r\n"
                + "if \"%1 %2\"==\"compose version\" exit /b 0\r\n"
                + "if not defined FAKE_RECORDED_MODE set FAKE_RECORDED_MODE=starter-pack\r\n"
                + "echo %* | %SystemRoot%\\System32\\findstr.exe /c:\"psql\" > nul && (echo %FAKE_RECORDED_MODE%& exit /b 0)\r\n"
                + "set pw=\r\n"
                + "set /p pw=\r\n"
                + "echo podman run --name kilnmark_x -e KC_ADMIN_PASSWORD=" + LEAKED + " quay.io/keycloak/keycloak 1>&2\r\n"
                + "echo ['podman', 'run', '-e', 'KILNMARK_OIDC_CLIENT_SECRET=" + LEAKED + "'] 1>&2\r\n"
                + "if defined pw echo keycloak-setup: got a password\r\n"
                + "echo keycloak-setup: seen [%KILNMARK_MODE%] [%KILNMARK_STARTER%]\r\n"
                + "if not defined KILNMARK_PEOPLE_ACTION exit /b " + exit + "\r\n"
                + "echo keycloak-setup: person [%KILNMARK_PEOPLE_ACTION%] [%KILNMARK_PERSON_EMAIL%] [%KILNMARK_PERSON_FIRST_NAME%] [%KILNMARK_PERSON_LAST_NAME%] [%KILNMARK_PERSON_GROUPS%]\r\n"
                + (ok ? "if \"%KILNMARK_PEOPLE_ACTION%\"==\"list\" (echo keycloak-setup: disabled old@bank.example: their temporary password was not used within 72 hours& echo keycloak-setup: person rita@bank.example groups=kilnmark-admins status=active must-choose-password=yes& echo keycloak-setup: people: 1)\r\n"
                    + "if \"%KILNMARK_PEOPLE_ACTION%\"==\"activate\" (echo keycloak-setup: activated %KILNMARK_PERSON_EMAIL%: they choose their own password at their first sign-in& exit /b 0)\r\n"
                    + "if \"%KILNMARK_PEOPLE_ACTION%\"==\"import\" (echo keycloak-setup: made priya.shah@example.com ^(kilnmark-designers;kilnmark-sensitive-data^): they choose their own password at their first sign-in& echo keycloak-setup: people import: made 1, already there 0, refused 0)\r\n"
                    + "if not \"%KILNMARK_PEOPLE_ACTION%\"==\"add\" exit /b 0\r\n"
                    + "if \"%KILNMARK_PERSON_EMAIL:~0,5%\"==\"here.\" (echo keycloak-setup: left %KILNMARK_PERSON_EMAIL% as they are: someone with this address is here already) else (echo keycloak-setup: made %KILNMARK_PERSON_EMAIL% ^(%KILNMARK_PERSON_GROUPS%^): they choose their own password at their first sign-in)\r\n"
                    : "if \"%KILNMARK_PEOPLE_ACTION%\"==\"import\" echo keycloak-setup: people import: made 0, already there 0, refused 2. Nothing was changed\r\n")
                + "exit /b " + exit + "\r\n");
            return f;
        }
        Path f = dir.resolve(name + ".sh");
        Files.writeString(f, "#!/bin/bash\n"
            + "if [ \"$1 $2\" = \"compose version\" ]; then exit 0; fi\n"
            + "case \"$*\" in *psql*) echo \"${FAKE_RECORDED_MODE:-starter-pack}\"; exit 0 ;; esac\n"
            + "pw=''; IFS= read -r -t 2 pw || true\n"
            + "echo \"podman run --name kilnmark_x -e KC_ADMIN_PASSWORD=" + LEAKED + " quay.io/keycloak/keycloak\" >&2\n"
            + "echo \"['podman', 'run', '-e', 'KILNMARK_OIDC_CLIENT_SECRET=" + LEAKED + "']\" >&2\n"
            + "[ -z \"$pw\" ] || echo \"keycloak-setup: got a password\"\n"
            + "echo \"keycloak-setup: seen [${KILNMARK_MODE-}] [${KILNMARK_STARTER-}]\"\n"
            + "if [ -n \"${KILNMARK_PEOPLE_ACTION-}\" ]; then\n"
            + "  echo \"keycloak-setup: person [$KILNMARK_PEOPLE_ACTION] [${KILNMARK_PERSON_EMAIL-}] [${KILNMARK_PERSON_FIRST_NAME-}] [${KILNMARK_PERSON_LAST_NAME-}] [${KILNMARK_PERSON_GROUPS-}]\"\n"
            + (ok ? "  case \"$KILNMARK_PEOPLE_ACTION\" in\n"
                + "    list) echo \"keycloak-setup: disabled old@bank.example: their temporary password was not used within 72 hours\"\n"
                + "      echo \"keycloak-setup: person rita@bank.example groups=kilnmark-admins status=active must-choose-password=yes\"; echo \"keycloak-setup: people: 1\" ;;\n"
                + "    activate) echo \"keycloak-setup: activated $KILNMARK_PERSON_EMAIL: they choose their own password at their first sign-in\" ;;\n"
                + "    import) echo \"keycloak-setup: lines [$(cat | wc -l | tr -d ' ')]\"\n"
                + "      echo \"keycloak-setup: made priya.shah@example.com (kilnmark-designers;kilnmark-sensitive-data): they choose their own password at their first sign-in\"\n"
                + "      echo \"keycloak-setup: people import: made 1, already there 0, refused 0\" ;;\n"
                + "    add) case \"$KILNMARK_PERSON_EMAIL\" in here.*) echo \"keycloak-setup: left $KILNMARK_PERSON_EMAIL as they are: someone with this address is here already\" ;;\n"
                + "      *) echo \"keycloak-setup: made $KILNMARK_PERSON_EMAIL ($KILNMARK_PERSON_GROUPS): they choose their own password at their first sign-in\" ;; esac ;;\n"
                + "  esac\n"
                : "  [ \"$KILNMARK_PEOPLE_ACTION\" != import ] || echo \"keycloak-setup: people import: made 0, already there 0, refused 2. Nothing was changed\"\n")
            + "fi\n"
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

    /** An earlier version beside the current one, its compose.yaml with these volume lines. */
    private static void older(Path root, String volumes) throws IOException {
        if (volumes != null) {
            Path v = Files.createDirectories(root.resolve("versions/9.9.8"));
            Files.writeString(v.resolve("compose.yaml"), "services:\n  kilnmark:\n"
                + "    image: ghcr.io/jporeilly/kilnmark-server:9.9.8\n    volumes:\n" + volumes);
        }
    }

    /** config/.env with these settings; a Starter Pack install unless they name KILNMARK_MODE. */
    private static void writeEnv(Path root, Map<String, String> settings) throws IOException {
        StringBuilder b = new StringBuilder("KILNMARK_DB_PASSWORD=db-0123456789\nKILNMARK_ADMIN_TOKEN=km_x\n");
        if (settings.keySet().stream().noneMatch(k -> k.endsWith("KILNMARK_MODE"))) {
            b.append("KILNMARK_MODE=starter-pack\n");
        }
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

    /** What the scripts read on their standard input: the example people's password, typed. */
    static String stdin = PEOPLE_PASSWORD;

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
        try (var in = p.getOutputStream()) {
            in.write((stdin + "\n").getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            // it read nothing and went on: fine
        }
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(p.waitFor(60, TimeUnit.SECONDS), "it stopped");
        assertTrue(engine.contains("engine-ok") || p.exitValue() != 0, "it cannot go on with no engine: " + out);
        return out;
    }
}

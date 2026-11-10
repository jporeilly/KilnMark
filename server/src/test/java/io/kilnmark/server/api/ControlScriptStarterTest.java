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
        assertTrue(shText.contains("--config \"$starter_work/curl.conf\" -X \"$1\""), "curl reads the token from that file");
        // every request with the token goes through api_call: the listener check, and 127.0.0.1
        assertEquals(1, shText.split("Authorization: Bearer", -1).length - 1, "one place sends the token");
        assertTrue(shText.contains("api_call POST /api/v1/starter-pack \"\""), "the pack is turned on through api_call");
        assertTrue(shText.contains("trap '[ -z \"$starter_work\" ] || rm -rf \"$starter_work\"' EXIT")
            && shText.contains("trap 'exit 130' INT") && shText.contains("trap 'exit 143' TERM")
            && shText.contains("trap 'exit 129' HUP"), "the folder goes however the script ends");
        assertTrue(shText.contains("--starter) with_starter=true"), "install --starter");

        String ps = Files.readString(LAUNCH.resolve("kilnmark.ps1"));
        assertTrue(ps.contains("[IO.File]::WriteAllText($configFile, $config"), "the token goes into curl's settings file");
        assertTrue(ps.contains("& curl.exe -k -s -K $configFile -X $Method"), "curl.exe reads it from there");
        assertEquals(1, ps.split("Authorization: Bearer", -1).length - 1, "one place sends the token");
        assertTrue(ps.contains("Invoke-KilnmarkApi 'POST' '/api/v1/starter-pack' '' $answerFile"), "the pack is turned on through Invoke-KilnmarkApi");
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
        assertFalse(c.contains("KILNMARK_SAMPLES_DB_PASSWORD:"), "never in an environment, and no default");
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
     * Kilnmark's container reaches the computer it runs on by both names, at KILNMARK_HOST_ADDRESS
     * or else the engine's own route; both control scripts drop a value set outside them, and the
     * Windows edition fills it in under WSL's mirrored networking, where host-gateway is the Podman
     * machine (docs/guide/install/models-on-this-computer.md).
     */
    @Test
    void kilnmarkReachesThisComputerAtTheHostAddress() throws IOException {
        String c = Files.readString(COMPOSE);
        String kilnmark = c.substring(c.indexOf("\n  kilnmark:\n"), c.indexOf("\n  keycloak-db:\n"));
        assertTrue(kilnmark.contains("    extra_hosts:\n      - \"host.docker.internal:${KILNMARK_HOST_ADDRESS:-host-gateway}\"\n"
            + "      - \"host.containers.internal:${KILNMARK_HOST_ADDRESS:-host-gateway}\"\n"), "both names, one address");
        String sh = Files.readString(LAUNCH.resolve("kilnmark.sh"));
        String unset = sh.substring(sh.indexOf("\nunset KILNMARK_MODE "), sh.indexOf('\n', sh.indexOf("KILNMARK_OIDC_ADMIN_GROUPS KILNMARK_OIDC_UNMASK_GROUPS")));
        assertTrue(unset.contains(" KILNMARK_HOST_ADDRESS"), "Linux drops a host address set outside the script");
        String ps = Files.readString(LAUNCH.resolve("kilnmark.ps1")).replace("\r\n", "\n");
        assertTrue(ps.contains("'KILNMARK_SECRETS_DIR', 'KILNMARK_HOST_ADDRESS', 'KILNMARK_KEYCLOAK_BIND'"), "Windows drops one set outside the script");
        String set = ps.substring(ps.indexOf("function Set-HostAddress {"), ps.indexOf("\n}\n", ps.indexOf("function Set-HostAddress {")));
        assertTrue(set.contains("if (Get-Setting 'KILNMARK_HOST_ADDRESS') { return }"), "config\\.env's own address wins");
        assertTrue(set.contains("if (-not (Test-WslMirrored)) { return }"), "only under mirrored networking");
        assertTrue(ps.contains("    $env:KILNMARK_SECRETS_DIR = $rel\n    Set-HostAddress\n"), "before every compose command");
    }

    /**
     * No Keycloak credential reaches the kilnmark container, whatever config/.env says: its service
     * names no Keycloak setting and no password or secret at all, takes no env_file, and the one file
     * of secrets mounted into it is the one the control script writes for it alone, holding only its
     * own four secrets (the fake engines check that file's lines).
     */
    @Test
    void noKeycloakCredentialReachesKilnmarkInAnyConfiguration() throws IOException {
        String c = Files.readString(COMPOSE);
        String kilnmark = c.substring(c.indexOf("\n  kilnmark:\n"), c.indexOf("\n  keycloak-db:\n"));
        assertFalse(kilnmark.contains("env_file:"), "no env_file: podman-compose puts its lines on podman's command line");
        assertEquals(1, kilnmark.split(":/run/kilnmark/env:", -1).length - 1, "one file of secrets");
        assertTrue(kilnmark.contains("      - ${KILNMARK_SECRETS_DIR:?Start Kilnmark with its control script, which writes each service secrets file and sets KILNMARK_SECRETS_DIR}/kilnmark.env:/run/kilnmark/env:ro,z\n"),
            "only the file the control script writes for Kilnmark");
        String sh = Files.readString(LAUNCH.resolve("kilnmark.sh"));
        assertTrue(sh.contains("      kilnmark) service_secret_line QUARKUS_DATASOURCE_PASSWORD KILNMARK_DB_PASSWORD\n"
            + "        service_secret_line KILNMARK_ADMIN_TOKEN KILNMARK_ADMIN_TOKEN\n"
            + "        service_secret_line KILNMARK_SAMPLES_DB_PASSWORD KILNMARK_SAMPLES_DB_PASSWORD\n"
            + "        service_secret_line QUARKUS_OIDC_CREDENTIALS_SECRET KILNMARK_OIDC_CLIENT_SECRET ;;\n"),
            "Kilnmark's file: its own four secrets, nothing of Keycloak's");
        for (String line : kilnmark.split("\n")) {
            String code = line.strip();
            if (code.startsWith("#") || !code.contains(":") || !code.contains("${")) {
                continue;
            }
            String from = code.substring(code.indexOf("${") + 2).replaceAll("[:}?-].*$", "");
            assertFalse(from.contains("KEYCLOAK"), "a Keycloak setting reaches Kilnmark: " + code);
            assertFalse(from.contains("STARTER"), "a Starter Pack people setting reaches Kilnmark: " + code);
            // (the folder of the services' secrets files is not a secret: the mount names it, above)
            assertFalse(!from.equals("KILNMARK_SECRETS_DIR") && (from.contains("PASSWORD") || from.contains("SECRET") || from.endsWith("_TOKEN")),
                "a secret in Kilnmark's environment: " + code);
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
        // command line: each reads its own few from a file the control script writes for the run,
        // mounted read-only, never config/.env whole; and neither has any power.
        String samples = c.substring(c.indexOf("\n  samples-db:\n"), c.indexOf("\n  kilnmark:\n"));
        assertFalse(c.contains("config/.env}:") || c.contains("KILNMARK_ENV_FILE"), "config/.env is never mounted");
        assertFalse(setup.contains("user: \"0\""), "keycloak-setup runs as the image's own user");
        for (String[] one : new String[][] {{"keycloak-setup", setup}, {"samples-db", samples}}) {
            assertTrue(one[1].contains("- ${KILNMARK_RUN_DIR:-../../config/run}/" + one[0] + ".env:/run/kilnmark/env:ro,z"),
                one[0]);
            assertTrue(one[1].contains("    cap_drop: [ALL]\n    security_opt: [no-new-privileges]\n"), one[0]);
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

    /**
     * No secret is a setting of any container (ADR 0051, decision 13): compose tools put each one on
     * podman's command line, which any account on the machine may read in /proc, and podman-compose
     * 1.6 does so for an env_file's lines too. The services that keep running read theirs from a file
     * the control script writes for each, mounted read-only, through kilnmark-env.sh, which compose
     * runs in place of the image's entrypoint and which then runs that entrypoint; the one-offs from
     * a file mounted for one run.
     */
    @Test
    void noSecretIsInAnyServicesEnvironment() throws IOException {
        String c = Files.readString(COMPOSE);
        assertEquals(List.of(), secretsInEnvironments(c), "a secret in a service's environment");
        // the check sees one: as compose.release.yaml had it before 0.1.2's review
        assertEquals(List.of("postgres: POSTGRES_PASSWORD: ${KILNMARK_DB_PASSWORD:?Set it}"),
            secretsInEnvironments("services:\n  postgres:\n    image: x\n    environment:\n      POSTGRES_DB: kilnmark\n"
                + "      POSTGRES_PASSWORD: ${KILNMARK_DB_PASSWORD:?Set it}\n    volumes:\n      - pgdata:/var/lib/postgresql\n"));
        assertEquals(List.of("kilnmark: KILNMARK_ADMIN_TOKEN: ${KILNMARK_ADMIN_TOKEN}"),
            secretsInEnvironments("services:\n  kilnmark:\n    environment:\n      KILNMARK_ADMIN_TOKEN: ${KILNMARK_ADMIN_TOKEN}\n"));
        // no env_file anywhere; each service with a secret reads it from its own file, mounted, which
        // the control scripts write, through kilnmark-env.sh, then the image's own entrypoint
        assertFalse(Pattern.compile("(?m)^\\s+env_file:").matcher(c).find(), "no env_file");
        String server = Files.readString(Path.of("../containers/server/Containerfile"));
        String postgres = Files.readString(Path.of("../containers/postgres/Containerfile"));
        Map<String, String> own = Map.of(
            "postgres", "\"kilnmark-tls.sh\"]\n    command: [\"postgres\"]\n",
            "keycloak-db", "\"kilnmark-tls.sh\"]\n    command: [\"postgres\"]\n",
            "kilnmark", "\"java\", \"-XX:+UseZGC\", \"-jar\", \"quarkus-run.jar\"]\n",
            "keycloak", "\"/opt/keycloak/bin/kc.sh\"]\n    command: [\"start\"]\n");
        assertTrue(server.contains("ENTRYPOINT [\"java\", \"-XX:+UseZGC\", \"-jar\", \"quarkus-run.jar\"]\n"), "the server image's own entrypoint");
        assertTrue(postgres.contains("ENTRYPOINT [\"kilnmark-tls.sh\"]\nCMD [\"postgres\"]\n"), "the database image's own");
        for (String service : List.of("postgres", "kilnmark", "keycloak-db", "keycloak")) {
            String block = serviceBlock(c, service);
            assertTrue(block.contains("      - ${KILNMARK_SECRETS_DIR:?Start Kilnmark with its control script, which writes each service secrets file and sets KILNMARK_SECRETS_DIR}/" + service + ".env:/run/kilnmark/env:ro,z\n"),
                service + " reads its secrets from its own file, mounted");
            assertTrue(block.contains("      - ./containers/kilnmark-env.sh:/run/kilnmark/start.sh:ro,z\n"), service + ": kilnmark-env.sh");
            assertTrue(block.contains("    entrypoint: [\"/bin/bash\", \"/run/kilnmark/start.sh\", " + own.get(service)),
                service + " starts through kilnmark-env.sh, then its image's own entrypoint and command");
        }
        String sh = Files.readString(LAUNCH.resolve("kilnmark.sh"));
        String ps = Files.readString(LAUNCH.resolve("kilnmark.ps1"));
        for (String f : List.of("postgres", "kilnmark", "keycloak-db", "keycloak")) {
            assertTrue(sh.contains("for f in postgres kilnmark keycloak-db keycloak; do") && ps.contains("'" + f + ".env' = @("), f);
        }
        // written before every compose command, so every start (up, restart, upgrade, configure's
        // restart, install's start, the hourly run) has them
        assertTrue(sh.contains("  write_service_secrets\n  compose=(\"$engine\" compose -p \"$project\" --env-file \"$compose_env\" -f \"$dir/compose.yaml\")\n"),
            "set_compose writes them first, and compose reads config/.env without its secrets");
        assertTrue(ps.replace("\r\n", "\n").contains("    Write-ServiceSecrets $dir\n    $list = @(Get-ComposeLead) + @('-p', $Project, '--env-file', $script:ComposeEnv,"),
            "Get-ComposeArgs too");
        assertTrue(sh.contains("export KILNMARK_SECRETS_DIR=\"$dir\"") && ps.contains("$env:KILNMARK_SECRETS_DIR = $rel"));
    }

    /**
     * kilnmark-env.sh, run for real: each NAME=value line of its file, taken as it is (spaces, #,
     * quotes, $ and \ too), goes into the environment of the program it then runs, and nowhere
     * else; a file that is not there, or a line that is not NAME=value, stops the service and says
     * why, never showing a value.
     */
    @Test
    void kilnmarkEnvHandsEachSecretToTheProgramAsItIs() throws Exception {
        String bash = System.getenv("KILNMARK_TEST_BASH");
        boolean windows = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win");
        String shell = bash != null && !bash.isBlank() ? bash : windows ? null : "bash";
        Assumptions.assumeTrue(shell != null, "set KILNMARK_TEST_BASH to bash to run this on Windows");
        Path dir = Files.createTempDirectory("kilnmark-env");
        Path env = dir.resolve("env");
        Path script = dir.resolve("start.sh");
        Files.writeString(script, Files.readString(Path.of("../containers/kilnmark-env.sh"))
            .replace("f=/run/kilnmark/env", "f='" + env.toString().replace('\\', '/') + "'"));
        Files.writeString(env, "A_PASSWORD=x y #z\nB_TOKEN='q\"$HOME\\n\n\nC=\n");
        String s = script.toString().replace('\\', '/');
        // the program it runs, as a script file: Windows would mangle the quotes of a -c argument
        Path show = dir.resolve("show.sh");
        Files.writeString(show, "printf '[%s][%s][%s]' \"$A_PASSWORD\" \"$B_TOKEN\" \"${C-unset}\"\n");
        Process p = new ProcessBuilder(shell, s, shell, show.toString().replace('\\', '/')).redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(0, p.waitFor(), out);
        assertEquals("[x y #z]['q\"$HOME\\n][]", out);
        for (String[] bad : new String[][] {{"no equals sign here\n", "line 1 of"}, {"lower=case\n", "line 1 of"}, {null, "are not there"}}) {
            if (bad[0] == null) {
                Files.delete(env);
            } else {
                Files.writeString(env, bad[0]);
            }
            Process q = new ProcessBuilder(shell, s, shell, "-c", "echo ran").redirectErrorStream(true).start();
            String said = new String(q.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            assertEquals(78, q.waitFor(), said);
            assertTrue(said.contains(bad[1]) && !said.contains("ran") && !said.contains("case"), said);
        }
    }

    /**
     * podman-compose itself, where KILNMARK_TEST_PODMAN_COMPOSE names it (podman-compose 1.6 is
     * what `podman compose` runs on a Podman machine): `--dry-run up` with every profile and a
     * podman that answers nothing prints every podman command it would run, and none holds a
     * secret's value or its name. And the check fails where it should: the same command over the
     * compose file as it was before 0.1.2's reviews puts them there.
     */
    @Test
    void podmanComposePutsNoSecretOnPodmansCommandLine() throws Exception {
        String pc = System.getenv("KILNMARK_TEST_PODMAN_COMPOSE");
        Assumptions.assumeTrue(pc != null && !pc.isBlank(), "set KILNMARK_TEST_PODMAN_COMPOSE to podman-compose to run this");
        String now = Files.readString(COMPOSE);
        List<String> seen = podmanComposeLeaks(pc, now);
        assertEquals(List.of(), seen, "secrets on podman's command line");
        String before = now.substring(0, now.indexOf("\nservices:\n")) + "\nservices:\n  postgres:\n"
            + "    image: ghcr.io/jporeilly/kilnmark-postgres:@VERSION@\n    environment:\n      POSTGRES_PASSWORD: ${KILNMARK_DB_PASSWORD}\n"
            + "  kilnmark:\n    image: ghcr.io/jporeilly/kilnmark-server:@VERSION@\n"
            + "    env_file:\n      - ${KILNMARK_SECRETS_DIR:?Start Kilnmark with its control script, which writes each service secrets file and sets KILNMARK_SECRETS_DIR}/kilnmark.env\n";
        assertTrue(podmanComposeLeaks(pc, before).containsAll(List.of("value of KILNMARK_DB_PASSWORD", "value of KILNMARK_ADMIN_TOKEN",
            "name POSTGRES_PASSWORD", "name KILNMARK_ADMIN_TOKEN")), "the check sees an environment's secret, and an env_file's");
    }

    /**
     * Windows, with Podman and podman-compose as its compose tool: podman-compose runs podman by its
     * bare name unless it is given --podman-path, so Windows would look for podman.exe in Python's
     * folder, the current folder and PATH again, past the one the hourly run's checks passed. The
     * control script hands it the checked path: a podman.exe planted earlier on PATH never runs,
     * and the checked engine does. Real podman-compose, a fake podman that passes compose on to it.
     */
    @Test
    void podmanComposeIsHandedTheCheckedPodman() throws Exception {
        String powershell = System.getenv("KILNMARK_TEST_POWERSHELL");
        String pc = System.getenv("KILNMARK_TEST_PODMAN_COMPOSE");
        Assumptions.assumeTrue(powershell != null && !powershell.isBlank() && pc != null && !pc.isBlank(),
            "set KILNMARK_TEST_POWERSHELL and KILNMARK_TEST_PODMAN_COMPOSE to run this");
        // a podman.exe that only notes it ran, earlier on PATH than anything else
        Path evil = Files.createTempDirectory("kilnmark-planted");
        Path ran = evil.resolve("ran.txt");
        Path build = evil.resolve("build.ps1");
        Files.writeString(build, "Add-Type -OutputType ConsoleApplication -OutputAssembly '" + evil.resolve("podman.exe")
            + "' -TypeDefinition 'public static class P { public static void Main() { System.IO.File.WriteAllText(@\"" + ran
            + "\", \"ran\"); } }'\r\n");
        Process b = new ProcessBuilder(powershell, "-NoProfile", "-ExecutionPolicy", "Bypass", "-File", build.toString()).redirectErrorStream(true).start();
        String built = new String(b.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(0, b.waitFor(), built);
        assertTrue(Files.exists(evil.resolve("podman.exe")), built);
        // the checked engine: answers podman's questions, passes compose on to its compose tool
        Path bin = Files.createTempDirectory("kilnmark-engine-ok-podman");
        Path used = bin.resolve("used.log");
        Files.writeString(bin.resolve("podman.cmd"), "@echo off\r\n"
            + "if \"%1\"==\"compose\" goto compose\r\n"
            + "echo %*>> \"" + used + "\"\r\n"
            + "if \"%1\"==\"--version\" (echo podman version 5.4.2& exit /b 0)\r\n"
            + "if \"%1\"==\"version\" (echo 5.4.2& exit /b 0)\r\n"
            + "if \"%1\"==\"ps\" (echo []& exit /b 0)\r\n"
            + "exit /b 0\r\n"
            + ":compose\r\n"
            + "set rest=%*\r\n"
            + "call set rest=%%rest:*compose =%%\r\n"
            + "\"%PODMAN_COMPOSE_PROVIDER%\" %rest%\r\n"
            + "exit /b %ERRORLEVEL%\r\n");
        Path root = installRoot(true);
        writeEnv(root, Map.of("KILNMARK_MODE", "production"));
        String system = System.getenv("SystemRoot");
        Map<String, String> env = new LinkedHashMap<>();
        env.put("PATH", evil + ";" + system + "\\System32;" + system + ";" + system + "\\System32\\WindowsPowerShell\\v1.0");
        env.put("PODMAN_COMPOSE_PROVIDER", pc);
        List<String> c = new ArrayList<>(List.of(powershell, "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass", "-File",
            LAUNCH.resolve("kilnmark.ps1").toAbsolutePath().toString(), "compose", "ps"));
        String out = run(c, root, bin.resolve("podman.cmd").toString(), env);
        assertFalse(Files.exists(ran), "the podman.exe planted on PATH ran: " + out);
        assertTrue(Files.exists(used) && Files.readString(used).contains("ps"), "the checked podman ran podman-compose's ps: " + out);
        // and the check can fail: podman-compose by itself, not handed the path, runs the planted one
        ProcessBuilder bare = new ProcessBuilder(pc, "-f", root.resolve("versions/9.9.9/compose.yaml").toString(), "ps").redirectErrorStream(true);
        bare.environment().put("PATH", env.get("PATH"));
        Process q = bare.start();
        q.getInputStream().readAllBytes();
        q.waitFor(60, TimeUnit.SECONDS);
        assertTrue(Files.exists(ran), "podman-compose ran podman by its bare name");
    }

    /** What of the secrets podman-compose --dry-run up prints over {@code compose}, with throwaway values. */
    static List<String> podmanComposeLeaks(String pc, String compose) throws Exception {
        boolean windows = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win");
        Path root = Files.createTempDirectory("kilnmark-dry");
        Path v = Files.createDirectories(root.resolve("versions/9.9.9"));
        Files.createDirectories(v.resolve("containers/keycloak/themes/kilnmark"));
        Files.writeString(v.resolve("compose.yaml"), compose.replace("@VERSION@", "9.9.9"));
        Files.copy(Path.of("../containers/kilnmark-env.sh"), v.resolve("containers/kilnmark-env.sh"));
        Files.writeString(v.resolve("containers/keycloak/setup.sh"), "# setup\n");
        Map<String, String> secrets = new LinkedHashMap<>();
        secrets.put("KILNMARK_DB_PASSWORD", "throwawayDb1aaaa");
        secrets.put("KILNMARK_ADMIN_TOKEN", "km_throwawayToken2bbbb");
        secrets.put("KILNMARK_SAMPLES_DB_PASSWORD", "throwawaySamples3cccc");
        secrets.put("KILNMARK_OIDC_CLIENT_SECRET", "throwawayClient4dddd");
        secrets.put("KILNMARK_KEYCLOAK_DB_PASSWORD", "throwawayKcDb5eeee");
        secrets.put("KILNMARK_KEYCLOAK_ADMIN_PASSWORD", "throwawayKcAdmin6ffff");
        Path services = Files.createDirectories(root.resolve("config/run/services"));
        Path run = Files.createDirectories(root.resolve("config/run/r.test"));
        StringBuilder env = new StringBuilder("KILNMARK_MODE=starter-pack\nCOMPOSE_PROFILES=auth\n");
        secrets.forEach((k, val) -> env.append(k).append('=').append(val).append('\n'));
        Files.writeString(root.resolve("config/.env"), env.toString());
        Files.writeString(services.resolve("postgres.env"), "POSTGRES_PASSWORD=" + secrets.get("KILNMARK_DB_PASSWORD") + "\n");
        Files.writeString(services.resolve("kilnmark.env"), "QUARKUS_DATASOURCE_PASSWORD=" + secrets.get("KILNMARK_DB_PASSWORD")
            + "\nKILNMARK_ADMIN_TOKEN=" + secrets.get("KILNMARK_ADMIN_TOKEN") + "\nKILNMARK_SAMPLES_DB_PASSWORD=" + secrets.get("KILNMARK_SAMPLES_DB_PASSWORD")
            + "\nQUARKUS_OIDC_CREDENTIALS_SECRET=" + secrets.get("KILNMARK_OIDC_CLIENT_SECRET") + "\n");
        Files.writeString(services.resolve("keycloak-db.env"), "POSTGRES_PASSWORD=" + secrets.get("KILNMARK_KEYCLOAK_DB_PASSWORD") + "\n");
        Files.writeString(services.resolve("keycloak.env"), "KC_DB_PASSWORD=" + secrets.get("KILNMARK_KEYCLOAK_DB_PASSWORD")
            + "\nKC_BOOTSTRAP_ADMIN_PASSWORD=" + secrets.get("KILNMARK_KEYCLOAK_ADMIN_PASSWORD") + "\n");
        Files.writeString(run.resolve("keycloak-setup.env"), "KILNMARK_KEYCLOAK_ADMIN_PASSWORD='" + secrets.get("KILNMARK_KEYCLOAK_ADMIN_PASSWORD")
            + "'\nKILNMARK_OIDC_CLIENT_SECRET='" + secrets.get("KILNMARK_OIDC_CLIENT_SECRET") + "'\n");
        Files.writeString(run.resolve("samples-db.env"), "KILNMARK_DB_PASSWORD='" + secrets.get("KILNMARK_DB_PASSWORD")
            + "'\nKILNMARK_SAMPLES_DB_PASSWORD='" + secrets.get("KILNMARK_SAMPLES_DB_PASSWORD") + "'\n");
        // a podman that answers nothing: no machine, no container, nothing is run
        Path fake;
        if (windows) {
            fake = root.resolve("podman.cmd");
            Files.writeString(fake, "@echo off\r\nif \"%1\"==\"--version\" (echo podman version 5.4.2& exit /b 0)\r\n"
                + "if \"%1\"==\"version\" (echo 5.4.2& exit /b 0)\r\nif \"%1\"==\"ps\" (echo []& exit /b 0)\r\n"
                + "if \"%2\"==\"inspect\" (echo []& exit /b 0)\r\nexit /b 0\r\n");
        } else {
            fake = root.resolve("podman");
            Files.writeString(fake, "#!/bin/sh\ncase \"$1\" in --version) echo 'podman version 5.4.2' ;; version) echo 5.4.2 ;; ps) echo '[]' ;;"
                + " *) [ \"${2-}\" != inspect ] || echo '[]' ;; esac\nexit 0\n");
            fake.toFile().setExecutable(true);
        }
        ProcessBuilder pb = new ProcessBuilder(pc, "--verbose", "--dry-run", "--podman-path", fake.toString(), "-p", "kmdry",
            "--env-file", root.resolve("config/.env").toString(), "-f", v.resolve("compose.yaml").toString(),
            "--profile", "auth", "--profile", "starter-pack", "up", "-d").redirectErrorStream(true).directory(root.toFile());
        pb.environment().put("KILNMARK_SECRETS_DIR", "../../config/run/services");
        pb.environment().put("KILNMARK_RUN_DIR", "../../config/run/r.test");
        Process p = pb.start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(p.waitFor(120, TimeUnit.SECONDS), "podman-compose stopped");
        assertEquals(0, p.exitValue(), "podman-compose --dry-run up failed");
        assertTrue(out.contains(" create ") || out.contains("'create'"), "podman-compose printed what it would create");
        List<String> leaks = new ArrayList<>();
        secrets.forEach((k, val) -> {
            if (out.contains(val)) {
                leaks.add("value of " + k);
            }
        });
        for (String n : List.of("POSTGRES_PASSWORD", "QUARKUS_DATASOURCE_PASSWORD", "KILNMARK_ADMIN_TOKEN", "KILNMARK_SAMPLES_DB_PASSWORD",
            "QUARKUS_OIDC_CREDENTIALS_SECRET", "KC_DB_PASSWORD", "KC_BOOTSTRAP_ADMIN_PASSWORD", "KILNMARK_DB_PASSWORD", "KILNMARK_OIDC_CLIENT_SECRET",
            "KILNMARK_KEYCLOAK_DB_PASSWORD", "KILNMARK_KEYCLOAK_ADMIN_PASSWORD")) {
            if (Pattern.compile("(?<![A-Z_])" + n + "(?![A-Z_])").matcher(out).find()) {
                leaks.add("name " + n);
            }
        }
        return leaks;
    }

    /** Each line of a service's environment naming a secret (its own name or the setting it takes), as "service: line". */
    static List<String> secretsInEnvironments(String compose) {
        List<String> out = new ArrayList<>();
        String service = "";
        boolean inEnv = false;
        Pattern secret = Pattern.compile("(PASSWORD|SECRET|_TOKEN)\\b");
        for (String line : compose.split("\n")) {
            Matcher svc = Pattern.compile("^  ([a-z][a-z0-9-]*):\\s*$").matcher(line);
            if (svc.matches()) {
                service = svc.group(1);
                inEnv = false;
                continue;
            }
            if (line.matches("^    [a-z_]+:.*$")) {
                inEnv = line.startsWith("    environment:");
                continue;
            }
            if (!line.startsWith("      ") || line.strip().startsWith("#")) {
                if (!line.isBlank() && !line.startsWith("      ") && !line.strip().startsWith("#")) {
                    inEnv = false;
                }
                continue;
            }
            if (!inEnv) {
                continue;
            }
            String code = line.strip();
            String key = code.contains(":") ? code.substring(0, code.indexOf(':')) : code;
            boolean named = secret.matcher(key).find();
            Matcher refs = Pattern.compile("\\$\\{([A-Z0-9_]+)").matcher(code);
            while (refs.find()) {
                named |= secret.matcher(refs.group(1)).find();
            }
            if (named) {
                out.add(service + ": " + code);
            }
        }
        return out;
    }

    /** One service's lines in a compose file, up to the next service. */
    static String serviceBlock(String compose, String service) {
        int at = compose.indexOf("\n  " + service + ":\n");
        assertTrue(at >= 0, service);
        Matcher next = Pattern.compile("\n  [a-z][a-z0-9-]*:\n|\n  #|\nvolumes:").matcher(compose);
        int end = next.find(at + 1) ? next.start() : compose.length();
        return compose.substring(at, end);
    }

    /** The example people's password as the tests type it, on the scripts' standard input. */
    static final String PEOPLE_PASSWORD = "typed-people-password-0123";

    @Test
    void thePeoplesPasswordIsTypedOrPipedInAndNeverShown() throws IOException {
        String sh = Files.readString(LAUNCH.resolve("kilnmark.sh"));
        assertTrue(sh.contains("  out=\"$(printf '%s\\n' \"$people_password\" | KILNMARK_STARTER_PEOPLE_ACTION=\"$action\" "
            + "KILNMARK_MODE=starter-pack \\\n    KILNMARK_INSTALL_ID=\"$id\" \"${compose[@]}\" run --rm --no-deps -T keycloak-setup 2>&1 "
            + "| hide_compose_commands)\" || ok=false"),
            "on keycloak-setup's standard input, from printf (a builtin: no command line)");
        assertTrue(sh.contains("read -r -s -p \"A password for the example people"), "typed with the echo off");
        String ps = Files.readString(LAUNCH.resolve("kilnmark.ps1")).replace("\r\n", "\n");
        assertTrue(ps.contains("$out = @($password | & $script:Engine @base run --rm --no-deps -T keycloak-setup 2>&1 |"),
            "on keycloak-setup's standard input");
        assertTrue(ps.contains("Read-Host -AsSecureString 'A password for the example people"), "typed, not shown");
        // a person's password the same way, beside the email, names and groups compose hands setup
        assertTrue(sh.contains("  run_people_setup add < <(printf '%s\\n' \"$person_password\") || ok=false")
            && sh.contains("run_people_setup import < <(printf '%s\\n' \"$person_password\"; cat -- \"$file\") || ok=false"),
            "on keycloak-setup's standard input, from printf (a builtin) and the file");
        assertTrue(sh.contains("read -r -s -p \"A temporary password for them")
            && sh.contains("read -r -s -p \"A temporary password for $1"), "typed with the echo off");
        assertTrue(ps.contains("$out = @($Lines | & $script:Engine @base run --rm --no-deps -T keycloak-setup 2>&1 |"),
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

    /**
     * What a people command printed, what logs/ kept, what it sent Kilnmark's API, and whom it asked
     * keycloak-setup to enable (a line each), by edition.
     */
    record Admin(String out, String logs, List<String> posted, String enabled) {
    }

    interface AdminRun {
        /**
         * {@code args}: the people command's words; {@code input}: the lines on its standard input;
         * {@code ok}: whether keycloak-setup works; {@code outside}: the script's own environment
         * (FAKE_RECORDED_MODE, the mode the database recorded, is config/.env's unless it says).
         */
        Admin run(Map<String, String> settings, List<String> args, String input, boolean ok, Map<String, String> outside)
            throws Exception;

        default Admin run(Map<String, String> settings, List<String> args, String input, boolean ok) throws Exception {
            return run(settings, args, input, ok, Map.of());
        }
    }

    /** The script's outside environment for a people command: the recorded mode config/.env's, unless {@code outside} says. */
    static Map<String, String> recordedAsEnv(Map<String, String> settings, Map<String, String> outside) {
        Map<String, String> env = new LinkedHashMap<>();
        env.put("FAKE_RECORDED_MODE", settings.getOrDefault("KILNMARK_MODE", "starter-pack"));
        env.putAll(outside);
        return env;
    }

    /**
     * A port on 127.0.0.1 nothing listens on, so a connection to it is refused: the scripts take only
     * a refused connection as Keycloak stopped. Port 9 is not that everywhere (a CI runner's answered).
     */
    static String closedPort() {
        try (java.net.ServerSocket s = new java.net.ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress())) {
            return Integer.toString(s.getLocalPort());
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
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
            // made disabled, and enabled once Kilnmark recorded them
            assertEquals("rita@bank.example", made.enabled(), made.out());
            assertFalse(made.out().contains("not enabled"), made.out());
        }
        // someone with that email already: left as they are, and recorded so
        Admin kept = a.run(WITH_KEYCLOAK, ADD, "here.rita@bank.example\nRita\nOkoro\nkilnmark-viewers\n" + PERSON_PASSWORD, true);
        assertTrue(kept.out().contains("left as they are: nothing was changed"), kept.out());
        assertEquals(List.of("{\"via\":\"add\",\"made\":[],\"kept\":[\"here.rita@bank.example\"],\"refused\":0}"),
            kept.posted());
        assertEquals("", kept.enabled(), "someone there already is never enabled");
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
        // Kilnmark must answer first, so nobody is made without a record (a closed port: nothing listens)
        for (List<String> args : List.of(ADD, List.of("people", "import", EXAMPLE_CSV), List.of("people", "activate", "rita@bank.example"))) {
            Admin silent = a.run(Map.of("KILNMARK_MODE", "production", "COMPOSE_PROFILES", "auth", "KILNMARK_PORT", closedPort()), args,
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
        assertEquals("rita@bank.example", activated.enabled(), "enabled once recorded");
        Admin notActivated = a.run(WITH_KEYCLOAK, List.of("people", "activate", "rita@bank.example"), PERSON_PASSWORD, false);
        assertTrue(notActivated.out().contains("Nobody was activated") && notActivated.posted().isEmpty(), notActivated.out());
        for (String[] bad : new String[][] {{"rita", PERSON_PASSWORD, "not an email address"},
            {"rita@bank.example", "short", "12 characters or more"},
            {"rita@bank.example", "tab\tinside-0123456789", "control character"}}) {
            Admin refused = a.run(WITH_KEYCLOAK, List.of("people", "activate", bad[0]), bad[1], true);
            assertTrue(refused.out().contains(bad[2]) && !refused.out().contains("keycloak-setup:"), refused.out());
        }
        // list: who is there, and who is waiting; anyone setup disabled for an unused password is recorded,
        // with the hours setup says the password could wait
        Admin list = a.run(WITH_KEYCLOAK, List.of("people", "list"), "", true);
        assertTrue(list.out().contains("rita@bank.example groups=kilnmark-admins status=active must-choose-password=yes")
            && list.out().contains("people: 1"), list.out());
        assertEquals(List.of("{\"via\":\"expire\",\"disabled\":[\"old@bank.example\"],\"hours\":72}"), list.posted());
        // keycloak-setup is handed the mode Kilnmark recorded, and its few secrets in a file for the
        // run, read from config/.env as compose reads it (quotes and comments), which goes after it
        Map<String, String> quoted = new LinkedHashMap<>(WITH_KEYCLOAK);
        quoted.put("KILNMARK_KEYCLOAK_ADMIN_PASSWORD", "\"kc-admin-1\" # quoted, with a comment");
        quoted.put("KILNMARK_OIDC_CLIENT_SECRET", "bare-secret # a comment");
        Admin handed = a.run(quoted, ADD, typed, true);
        assertTrue(handed.out().contains("keycloak-setup: seen [production]"), handed.out());
        assertTrue(handed.out().contains("keycloak-setup: secrets [as compose reads them]"), handed.out());
        // and the services that keep running theirs, each in its own file, none in their environment
        // (ADR 0051, decision 13): Kilnmark's holds none of Keycloak's
        assertTrue(handed.out().contains("services: secrets [as compose reads them]"), handed.out());
        assertFalse(handed.out().contains("kc-admin-1") || handed.out().contains("bare-secret"), "a secret was shown");
        // a database password or admin token missing: nothing starts, and that is said by its name
        Map<String, String> noToken = new LinkedHashMap<>(WITH_KEYCLOAK);
        noToken.put("KILNMARK_ADMIN_TOKEN", "");
        Admin noTokenRun = a.run(noToken, ADD, typed, true);
        assertTrue(noTokenRun.out().contains("KILNMARK_ADMIN_TOKEN is not set in") && !noTokenRun.out().contains("keycloak-setup: person"),
            noTokenRun.out());
        // the people commands act only where the record says config/.env's mode, and the database answers
        for (List<String> args : List.of(ADD, List.of("people", "import", EXAMPLE_CSV), List.of("people", "activate",
            "rita@bank.example"), List.of("people", "list"), List.of("people", "expire"))) {
            for (String[] recorded : new String[][] {{"starter-pack", "differs from the mode Kilnmark recorded"},
                {"none", "database does not answer"}}) {
                Admin refused = a.run(WITH_KEYCLOAK, args, typed, true, Map.of("FAKE_RECORDED_MODE", recorded[0]));
                assertTrue(refused.out().contains(recorded[1]) && !refused.out().contains("keycloak-setup: person"),
                    args + " " + recorded[0] + ": " + refused.out());
                assertTrue(refused.posted().isEmpty(), args.toString());
            }
        }
        // expire: what the hourly timer runs; who it disabled is recorded, 500 people a record
        Admin expired = a.run(WITH_KEYCLOAK, List.of("people", "expire"), "", true);
        assertTrue(expired.out().contains("disabled p1200@bank.example: their temporary password") && expired.out().contains("people expire: done"),
            expired.out());
        assertEquals(3, expired.posted().size(), "1200 people in three records");
        for (int i = 0; i < 3; i++) {
            String body = expired.posted().get(i);
            assertTrue(body.startsWith("{\"via\":\"expire\",\"disabled\":[\"p" + (i * 500 + 1) + "@bank.example\""), body);
            assertEquals(i < 2 ? 500 : 200, body.split("@bank.example", -1).length - 1, body);
            assertTrue(body.endsWith("],\"hours\":72}"), body);
        }
        // a person Kilnmark cannot record is never enabled (fail closed), and that is said
        Admin unrecorded = a.run(WITH_KEYCLOAK, ADD, "fail-me@bank.example\nFay\nIl\nkilnmark-viewers\n" + PERSON_PASSWORD, true);
        assertTrue(unrecorded.out().contains("Kilnmark did not record this in its audit log")
            && unrecorded.out().contains("So they stay disabled"), unrecorded.out());
        assertEquals("", unrecorded.enabled(), unrecorded.out());
        // nothing to do while Keycloak is stopped (a closed port: nothing listens)
        Map<String, String> stopped = new LinkedHashMap<>(WITH_KEYCLOAK);
        stopped.put("KILNMARK_KEYCLOAK_PORT", closedPort());
        Admin idle = a.run(stopped, List.of("people", "expire"), "", true);
        assertTrue(idle.out().contains("nothing was expired now") && !idle.out().contains("keycloak-setup:"), idle.out());
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
            // Kilnmark ready, and Keycloak's realm answering, for up and people expire
            for (String path : List.of("/q/health/ready", "/realms/kilnmark")) {
                server.createContext(path, x -> {
                    x.sendResponseHeaders(200, -1);
                    x.close();
                });
            }
            server.createContext("/api/v1/auth/people", x -> {
                String body = new String(x.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                posted.add(body);
                // a record Kilnmark cannot keep, for the people made with this address
                x.sendResponseHeaders(body.contains("fail-me@") ? 500 : 204, -1);
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

    /**
     * Windows PowerShell's transcript (logs\) keeps what a program writes to its error output even
     * when the script sends it to $null. A probe that finds what it often finds, nothing (the images
     * not there yet, no hourly task yet), leaves no error text there to read like a failure: it runs
     * through Invoke-Quiet. What the script itself says is still logged.
     */
    @Test
    void aProbeThatFindsNothingLeavesNoErrorInTheWindowsLog() throws Exception {
        String powershell = System.getenv("KILNMARK_TEST_POWERSHELL");
        Assumptions.assumeTrue(powershell != null && !powershell.isBlank(),
            "set KILNMARK_TEST_POWERSHELL to powershell.exe to run this");
        Path dir = Files.createTempDirectory("kilnmark-noisy-probes");
        // an engine with no images until FAKE_IMAGES is set, saying so on its error output as Podman does
        Path engine = dir.resolve("engine-ok.cmd");
        Files.writeString(engine, "@echo off\r\n"
            + "if \"%1 %2\"==\"image inspect\" if not defined FAKE_IMAGES (echo Error: unable to find the image: image not known 1>&2& exit /b 125)\r\n"
            + "exit /b 0\r\n");
        // a Task Scheduler with no task, saying so as schtasks.exe does
        Path schtasks = dir.resolve("schtasks.cmd");
        Files.writeString(schtasks, "@echo off\r\n"
            + "if /i \"%1\"==\"/Query\" (echo ERROR: The system cannot find the file specified. 1>&2& exit /b 1)\r\n"
            + "exit /b 0\r\n");
        List<String> up = List.of(powershell, "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass", "-File",
            LAUNCH.resolve("kilnmark.ps1").toAbsolutePath().toString(), "up", "-NoBrowser");
        try (Api api = Api.start()) {
            Path root = adminRoot(true, Map.of("KILNMARK_MODE", "production"), api);
            String missing = run(up, root, engine.toString(), Map.of("KILNMARK_SCHTASKS", schtasks.toString()));
            assertTrue(missing.contains("are not here"), missing);
            // with the images, up goes on to the hourly task, looked for to remove it (no bundled Keycloak)
            String started = run(up, root, engine.toString(), Map.of("KILNMARK_SCHTASKS", schtasks.toString(), "FAKE_IMAGES", "yes"));
            assertTrue(started.contains("is ready at"), started);
            String logs = logsOf(root);
            assertTrue(logs.contains("are not here") && logs.contains("is ready at"), "both runs were logged: " + logs);
            assertFalse(logs.contains("image not known"), "the image probe's error output was logged: " + logs);
            assertFalse(logs.contains("cannot find the file specified"), "the task probe's error output was logged: " + logs);
        }
    }

    /** An install root with these settings, Kilnmark's API at the test's server. */
    private static Path adminRoot(boolean windows, Map<String, String> settings, Api api) throws IOException {
        Path root = installRoot(windows);
        Map<String, String> all = new LinkedHashMap<>(settings);
        all.putIfAbsent("KILNMARK_PORT", String.valueOf(api.port()));
        all.putIfAbsent("KILNMARK_KEYCLOAK_PORT", String.valueOf(api.port()));
        writeEnv(root, all);
        return root;
    }

    /** Whom the fake keycloak-setup was asked to enable, a line each. */
    private static String enabledOf(Path root) throws IOException {
        Path f = root.resolve("enabled.log");
        return Files.exists(f) ? String.join("\n", Files.readAllLines(f).stream().map(String::strip).toList()).strip() : "";
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
            + "in_group someone 1 || exit 5\n! in_group someone x || exit 6\n"
            // what Keycloak does not answer is never taken for the safe answer
            + "for fn in federated no_credentials enabled_state in_pack_group; do eval \"$(sed -n \"/^$fn() {/,/^}/p\" \"$f\")\"; done\n"
            + "STARTER_GROUP=kilnmark-starter-pack\nkc() { return 1; }\n"
            + "federated someone || exit 7\n! no_credentials someone || exit 8\n[ -z \"$(enabled_state someone)\" ] || exit 9\n"
            + "rc=0; in_pack_group someone || rc=$?; [ \"$rc\" = 2 ] || exit 10\n");
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
            adminRules((settings, args, input, ok, outside) -> {
                Path root = adminRoot(false, settings, api);
                api.posted().clear();
                List<String> c = new ArrayList<>(List.of(shell, script));
                c.addAll(args.stream().map(w -> w.replace('\\', '/')).toList());
                String out = withInput(input, () -> run(c, root, (ok ? works : engine).toString().replace('\\', '/'),
                    recordedAsEnv(settings, outside)));
                noRunSecretsLeft(root);
                return new Admin(out, logsOf(root), List.copyOf(api.posted()), enabledOf(root));
            });
            timerRules(new TimerRun() {
                @Override
                public Timer run(Map<String, String> settings, List<String> args, Map<String, String> outside, String input)
                    throws Exception {
                    Path root = root();
                    writeEnv(root, withApi(settings, api));
                    List<String> c = new ArrayList<>(List.of(shell, script));
                    c.addAll(args);
                    String out = withInput(input, () -> ControlScriptStarterTest.run(c, root, works.toString().replace('\\', '/'),
                        recordedAsEnv(settings, outside)));
                    noRunSecretsLeft(root);
                    return new Timer(out, unitsOf(root), "");
                }

                @Override
                public Path root() {
                    return TIMER_ROOTS.computeIfAbsent("linux", k -> uncheckedRoot(false));
                }

                @Override
                public boolean windows() {
                    return false;
                }
            });
        }
    }

    /**
     * Windows (ADR 0051, decisions 15 and 17): what an hourly run uses, in the program folder and the
     * data folder, must be changeable by nobody but you, SYSTEM and the Administrators; every folder
     * on the way to it, and above each root, counts too; the admin token goes only to a listener of
     * yours.
     */
    @Test
    void theWindowsEditionKeepsTheHourlyRunsTreeItsOwn() throws Exception {
        String powershell = System.getenv("KILNMARK_TEST_POWERSHELL");
        Assumptions.assumeTrue(powershell != null && !powershell.isBlank(),
            "set KILNMARK_TEST_POWERSHELL to powershell.exe to run this");
        Path works = fakeEngine(true, true);
        List<String> ps = List.of(powershell, "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass", "-File",
            LAUNCH.resolve("kilnmark.ps1").toAbsolutePath().toString());
        List<String> up = new ArrayList<>(ps);
        up.addAll(List.of("up", "-NoBrowser"));
        List<String> expire = new ArrayList<>(ps);
        expire.addAll(List.of("people", "expire"));
        try (Api api = Api.start()) {
            // a root everyone may change: the task is not set up, and the hourly run refuses
            Path open = Files.createTempDirectory("kilnmark-open");
            icacls(open, "/grant", "*S-1-1-0:(OI)(CI)(W)");
            Path old = installRoot(true, open);
            writeEnv(old, withApi(WITH_KEYCLOAK, api));
            String first = run(up, old, works.toString(), recordedAsEnv(WITH_KEYCLOAK, Map.of()));
            assertTrue(first.contains("was not set up") && first.contains("may be changed by"), first);
            String refused = run(expire, old, works.toString(), recordedAsEnv(WITH_KEYCLOAK, Map.of()));
            assertTrue(refused.contains("people expire was refused") && refused.contains("may be changed by"), refused);
            // current.txt naming a folder outside versions\
            Path fine = installRoot(true);
            writeEnv(fine, withApi(WITH_KEYCLOAK, api));
            Files.writeString(fine.resolve("current.txt"), "..\\..\\elsewhere\n");
            String outside = run(expire, fine, works.toString(), recordedAsEnv(WITH_KEYCLOAK, Map.of()));
            assertTrue(outside.contains("people expire was refused") && outside.contains("does not name a version"), outside);
            Files.writeString(fine.resolve("current.txt"), "9.9.9\n");
            // data-folder.txt naming a data folder someone else may change: the hourly run refuses
            Path data = Files.createTempDirectory(open, "kilnmark-data");
            Files.createDirectories(data.resolve("config"));
            Files.writeString(data.resolve("config/.env"), Files.readString(fine.resolve("config/.env")));
            Files.writeString(fine.resolve("data-folder.txt"), data + "\n");
            String opened = run(expire, fine, works.toString(), recordedAsEnv(WITH_KEYCLOAK, Map.of()));
            assertTrue(opened.contains("people expire was refused") && opened.contains("may be changed by"), opened);
            Files.writeString(fine.resolve("data-folder.txt"), fine + "\n");
            // a folder above the root that lets everyone delete what is in it: the root could be swapped
            Path above = Files.createTempDirectory("kilnmark-above");
            Path under = installRoot(true, above);
            writeEnv(under, withApi(WITH_KEYCLOAK, api));
            icacls(above, "/grant", "*S-1-1-0:(DC)");
            String moved = run(expire, under, works.toString(), recordedAsEnv(WITH_KEYCLOAK, Map.of()));
            assertTrue(moved.contains("people expire was refused") && moved.contains(", a folder above "), moved);
            // and no admin token went anywhere: the refusal reads no settings
            assertTrue(api.posted().isEmpty(), "nothing was sent: " + api.posted());
            // a folder above the root that is a junction: it leads wherever someone made it lead
            Path real = Files.createTempDirectory("kilnmark-real");
            Path junction = Files.createTempDirectory("kilnmark-junction-parent").resolve("j");
            link(junction, real);
            Path through = installRoot(true, junction);
            writeEnv(through, withApi(WITH_KEYCLOAK, api));
            String linked = run(expire, through, works.toString(), recordedAsEnv(WITH_KEYCLOAK, Map.of()));
            assertTrue(linked.contains("people expire was refused") && linked.contains("is a link or a junction"), linked);
            // the engine the run starts, by its full path: one others may change is refused, as is one
            // in a folder others may add to (a library beside it would be loaded)
            Path tools = installRoot(true);
            writeEnv(tools, withApi(WITH_KEYCLOAK, api));
            Path loose = Files.createTempDirectory("kilnmark-loose-engine").resolve("engine-ok.cmd");
            Files.copy(works, loose);
            icacls(loose, "/grant", "*S-1-1-0:(W)");
            String looseOut = run(expire, tools, loose.toString(), recordedAsEnv(WITH_KEYCLOAK, Map.of()));
            assertTrue(looseOut.contains("people expire was refused") && looseOut.contains("engine-ok.cmd, a program an hourly run starts, may be changed by"),
                looseOut);
            Path openDir = Files.createTempDirectory("kilnmark-open-engine");
            Path inOpen = openDir.resolve("engine-ok.cmd");
            Files.copy(works, inOpen);
            icacls(openDir, "/grant", "*S-1-1-0:(AD)");
            String openOut = run(expire, tools, inOpen.toString(), recordedAsEnv(WITH_KEYCLOAK, Map.of()));
            assertTrue(openOut.contains("people expire was refused") && openOut.contains(openDir + ", the folder of a program an hourly run starts, may be changed by"), openOut);
            // Podman's compose tool (docker-compose, as PATH finds it) too; a clean one passes
            // (in a folder named engine-ok, as the tests' run asks of an engine that works)
            Path bin = Files.createTempDirectory("kilnmark-engine-ok-podman");
            Path podman = bin.resolve("podman.cmd");
            Files.copy(works, podman);
            Path dc = Files.createTempDirectory("kilnmark-compose-bin").resolve("docker-compose.cmd");
            Files.writeString(dc, "@echo off\r\nexit /b 0\r\n");
            String system = System.getenv("SystemRoot");
            Map<String, String> path = new LinkedHashMap<>(recordedAsEnv(WITH_KEYCLOAK, Map.of()));
            path.put("PATH", dc.getParent() + ";" + system + "\\System32;" + system + ";" + system + "\\System32\\WindowsPowerShell\\v1.0");
            // (whatever PODMAN_COMPOSE_PROVIDER says outside the tests)
            path.put("PODMAN_COMPOSE_PROVIDER", null);
            Path provider = Files.createTempFile("kilnmark-provider", ".log");
            path.put("FAKE_PROVIDER_LOG", provider.toString());
            String clean = run(expire, tools, podman.toString(), path);
            assertFalse(clean.contains("people expire was refused"), clean);
            // and Podman is told to launch that one, by the full path that was checked, and no other
            assertTrue(Files.readString(provider).contains("provider [" + dc + "]"), Files.readString(provider));
            icacls(dc, "/grant", "*S-1-5-11:(M)");
            String composeOut = run(expire, tools, podman.toString(), path);
            assertTrue(composeOut.contains("people expire was refused") && composeOut.contains("docker-compose.cmd"), composeOut);
            // a compose tool named that PATH does not find as a program: Podman would look for one itself, unchecked
            Map<String, String> unnamed = new LinkedHashMap<>(path);
            unnamed.put("PODMAN_COMPOSE_PROVIDER", "no-such-compose");
            String unfound = run(expire, tools, podman.toString(), unnamed);
            assertTrue(unfound.contains("people expire was refused") && unfound.contains("so it cannot be checked"), unfound);
            // the engine by a name: the program PATH finds, never a script of that name earlier on it, is
            // what is checked and what runs
            Path evil = Files.createTempDirectory("kilnmark-evil");
            Path marker = evil.resolve("ran.txt");
            Files.writeString(evil.resolve("engine-ok.ps1"), "Set-Content -LiteralPath '" + marker + "' 'ran'\r\n");
            Path good = Files.createTempDirectory("kilnmark-good");
            Files.copy(works, good.resolve("engine-ok.cmd"));
            Map<String, String> named = new LinkedHashMap<>(recordedAsEnv(WITH_KEYCLOAK, Map.of()));
            named.put("PATH", evil + ";" + good + ";" + system + "\\System32;" + system + ";" + system + "\\System32\\WindowsPowerShell\\v1.0");
            String byName = run(expire, tools, "engine-ok", named);
            assertFalse(byName.contains("people expire was refused") || Files.exists(marker), byName);
            assertTrue(byName.contains("people expire: done"), byName);
            // Docker's compose plugin, in a folder Docker looks in, that others may change
            Path dockerBin = Files.createTempDirectory("kilnmark-engine-ok-docker");
            Files.copy(works, dockerBin.resolve("docker.cmd"));
            Path home = Files.createTempDirectory("kilnmark-home");
            Path plugin = Files.createDirectories(home.resolve(".docker/cli-plugins")).resolve("docker-compose.exe");
            Files.writeString(plugin, "not a program");
            icacls(plugin, "/grant", "*S-1-5-11:(M)");
            // (ProgramData and Program Files as the tests make them: folders of yours alone)
            Path programData = Files.createTempDirectory("kilnmark-programdata");
            Path programFiles = Files.createTempDirectory("kilnmark-programfiles");
            Map<String, String> docker = new LinkedHashMap<>(recordedAsEnv(WITH_KEYCLOAK, Map.of()));
            docker.put("USERPROFILE", home.toString());
            docker.put("ProgramData", programData.toString());
            docker.put("ProgramFiles", programFiles.toString());
            String dockerOut = run(expire, tools, dockerBin.resolve("docker.cmd").toString(), docker);
            assertTrue(dockerOut.contains("people expire was refused") && dockerOut.contains("docker-compose.exe, a program an hourly run starts"),
                dockerOut);
            // with no plugin there, the folders Docker looks in all clean: it runs
            Files.delete(plugin);
            String dockerClean = run(expire, tools, dockerBin.resolve("docker.cmd").toString(), docker);
            assertFalse(dockerClean.contains("people expire was refused"), dockerClean);
            // a folder config.json adds (cliPluginsExtraDirs) that others may add to; and one that does not read
            Path extra = Files.createTempDirectory("kilnmark-extra-plugins");
            icacls(extra, "/grant", "*S-1-5-11:(AD)");
            Path config = home.resolve(".docker/config.json");
            Files.writeString(config, "{\"cliPluginsExtraDirs\": [\"" + extra.toString().replace("\\", "\\\\") + "\"]}");
            String extraOut = run(expire, tools, dockerBin.resolve("docker.cmd").toString(), docker);
            assertTrue(extraOut.contains("people expire was refused") && extraOut.contains(extra + ", a folder Docker runs its compose plugin from, may be changed by"),
                extraOut);
            Files.writeString(config, "{ not json");
            String unread = run(expire, tools, dockerBin.resolve("docker.cmd").toString(), docker);
            assertTrue(unread.contains("people expire was refused") && unread.contains("could not be read"), unread);
            Files.delete(config);
            // a plugin folder that is not there, where others may make it (as C:\ProgramData lets Users)
            icacls(programData, "/grant", "*S-1-5-11:(AD)");
            String missing = run(expire, tools, dockerBin.resolve("docker.cmd").toString(), docker);
            assertTrue(missing.contains("people expire was refused") && missing.contains(programData.resolve("Docker\\cli-plugins")
                + ", a folder Docker runs its compose plugin from, is not there") && missing.contains("make it as an administrator"), missing);
            // the admin token goes only to a listener of yours (port 135: Windows' own RPC service)
            Path elsewhereRoot = installRoot(true);
            Map<String, String> rpc = new LinkedHashMap<>(withApi(WITH_KEYCLOAK, api));
            rpc.put("KILNMARK_PORT", "135");
            writeEnv(elsewhereRoot, rpc);
            List<String> add = new ArrayList<>(ps);
            add.addAll(List.of("people", "add"));
            String notSent = run(add, elsewhereRoot, works.toString(), recordedAsEnv(WITH_KEYCLOAK, Map.of()));
            assertTrue(notSent.contains("listens on port 135") && notSent.contains("the admin token was not sent"), notSent);
        }
    }

    /**
     * Linux (ADR 0051, decision 15): every folder on the way to what an hourly run uses, and above the
     * root, counts; current leads into versions/; the run's log is never written through a link.
     */
    @Test
    void theLinuxEditionChecksTheHourlyRunsTreeAllTheWay() throws Exception {
        boolean windows = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win");
        Assumptions.assumeFalse(windows, "Git Bash keeps NTFS's permissions as they are, and links need a privilege");
        String script = LAUNCH.resolve("kilnmark.sh").toAbsolutePath().toString();
        String works = fakeEngine(false, true).toString();
        Map<String, String> settings = new LinkedHashMap<>(WITH_KEYCLOAK);
        settings.put("KILNMARK_KEYCLOAK_PORT", closedPort());
        Path parent = Files.createTempDirectory("kilnmark-parent");
        Path root = installRoot(false, parent);
        writeEnv(root, settings);
        List<String> expire = List.of("bash", script, "people", "expire");
        String fine = run(expire, root, works);
        assertTrue(fine.contains("nothing was expired now") && !fine.contains("was refused"), fine);
        // a folder above the root its group may write: the root could be moved away and another put there
        chmod(parent, "775");
        String moved = run(expire, root, works);
        assertTrue(moved.contains("people expire was refused") && moved.contains(", a folder above "), moved);
        String day = java.time.LocalDate.now(java.time.ZoneOffset.UTC).format(java.time.format.DateTimeFormatter.BASIC_ISO_DATE);
        String logged = Files.readString(root.resolve("logs/people-expire-" + day + ".log"));
        assertTrue(logged.contains("people expire was refused: ") && logged.contains(", a folder above "), logged);
        // sticky, as /tmp is, with the root the account's own: nobody else can move it
        chmod(parent, "1777");
        String sticky = run(expire, root, works);
        assertFalse(sticky.contains("was refused"), sticky);
        chmod(parent, "700");
        // the engine in a folder its group may change
        Path tools = Files.createTempDirectory("kilnmark-tools");
        Path engine = Files.copy(Path.of(works), tools.resolve("engine-ok.sh"));
        chmod(tools, "775");
        String engineOpen = run(expire, root, engine.toString());
        assertTrue(engineOpen.contains("people expire was refused") && engineOpen.contains(", a folder above "), engineOpen);
        chmod(tools, "700");
        // logs its group may write (up makes it the account's alone again; the hourly run is checked
        // before it opens its log)
        chmod(root.resolve("logs"), "g+w");
        String logs = run(expire, root, works);
        assertTrue(logs.contains("people expire was refused") && logs.contains("/logs may be changed"), logs);
        chmod(root.resolve("logs"), "700");
        // current leading out of versions/
        Path elsewhere = Files.createTempDirectory("kilnmark-elsewhere");
        Files.delete(root.resolve("current"));
        link(root.resolve("current"), elsewhere);
        String out = run(expire, root, works);
        assertTrue(out.contains("people expire was refused") && out.contains("does not lead to a version"), out);
        Files.delete(root.resolve("current"));
        link(root.resolve("current"), root.resolve("versions/9.9.9"));
        // the day's log a link: never written through
        Path target = Files.createTempFile("kilnmark-log-target", ".txt");
        Path log = root.resolve("logs/people-expire-" + java.time.LocalDate.now(java.time.ZoneOffset.UTC)
            .format(java.time.format.DateTimeFormatter.BASIC_ISO_DATE) + ".log");
        Files.deleteIfExists(log);
        Files.createSymbolicLink(log, target);
        String linked = run(expire, root, works);
        assertTrue(linked.contains("is not a plain file"), linked);
        assertEquals(0, Files.size(target), "nothing written through the link");
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
            adminRules((settings, args, input, ok, outside) -> {
                Path root = adminRoot(true, settings, api);
                api.posted().clear();
                List<String> c = new ArrayList<>(ps);
                c.addAll(args);
                String out = withInput(input, () -> run(c, root, (ok ? works : engine).toString(), recordedAsEnv(settings, outside)));
                noRunSecretsLeft(root);
                return new Admin(out, logsOf(root), List.copyOf(api.posted()), enabledOf(root));
            });
            timerRules(new TimerRun() {
                @Override
                public Timer run(Map<String, String> settings, List<String> args, Map<String, String> outside, String input)
                    throws Exception {
                    Path root = root();
                    writeEnv(root, withApi(settings, api));
                    List<String> c = new ArrayList<>(ps);
                    c.addAll(args.stream().map(a -> a.equals("--no-browser") ? "-NoBrowser" : a.equals("--purge") ? "-Purge" : a)
                        .toList());
                    String out = withInput(input, () -> ControlScriptStarterTest.run(c, root, works.toString(),
                        recordedAsEnv(settings, outside)));
                    noRunSecretsLeft(root);
                    Path log = root.resolve("schtasks.log");
                    return new Timer(out, "", Files.exists(log) ? Files.readString(log) : "");
                }

                @Override
                public Path root() {
                    return TIMER_ROOTS.computeIfAbsent("windows", k -> uncheckedRoot(true));
                }

                @Override
                public boolean windows() {
                    return true;
                }
            });
        }
    }

    /** What up, status and uninstall printed, the systemd units there (Linux), and what schtasks was asked (Windows). */
    record Timer(String out, String units, String schtasks) {
    }

    interface TimerRun {
        /**
         * {@code args}: the command's words, the same install root throughout, Kilnmark's API
         * answering; {@code outside}: the script's own environment; {@code input}: its standard input.
         */
        Timer run(Map<String, String> settings, List<String> args, Map<String, String> outside, String input) throws Exception;

        default Timer run(Map<String, String> settings, List<String> args) throws Exception {
            return run(settings, args, Map.of(), "");
        }

        /** The install root the runs share. */
        Path root();

        boolean windows();
    }

    static final Map<String, Path> TIMER_ROOTS = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * With the bundled Keycloak, up sets up the hourly people expire (a systemd timer on Linux, a
     * scheduled task on Windows), once; status and check show it; up without the bundled Keycloak
     * and uninstall remove it.
     */
    private static void timerRules(TimerRun t) throws Exception {
        Timer first = t.run(WITH_KEYCLOAK, List.of("up", "--no-browser"));
        assertTrue(first.out().contains("is ready at") && first.out().contains("Set up the hourly expiry"), first.out());
        if (!first.units().isEmpty()) {
            assertTrue(first.units().contains("/kilnmark.sh\" people expire\n") && first.units().contains("ExecStart=\"/")
                && first.units().contains("OnCalendar=hourly")
                && first.units().contains("Environment=KILNMARK_PROJECT=kilnmark"), first.units());
        } else {
            assertTrue(first.schtasks().contains("/Create /SC HOURLY /TN \"Kilnmark kilnmark people expire\"")
                && first.schtasks().contains("\\kilnmark.ps1\\\" people expire -Project kilnmark")
                && first.schtasks().contains("\\System32\\WindowsPowerShell\\v1.0\\powershell.exe\\\" -NoProfile"), first.schtasks());
        }
        // the install's id, as the server recorded it, handed to keycloak-setup at the start
        assertTrue(Files.readString(t.root().resolve("up.log")).contains("install id [0e38a49e-ee00-4b1e-98f9-fe3c44e6fa09]"),
            Files.readString(t.root().resolve("up.log")));
        Timer again = t.run(WITH_KEYCLOAK, List.of("up", "--no-browser"));
        assertFalse(again.out().contains("Set up the hourly expiry"), "once: " + again.out());
        assertTrue(t.run(WITH_KEYCLOAK, List.of("status")).out().contains("People expiry: hourly"));
        assertTrue(t.run(WITH_KEYCLOAK, List.of("check")).out().contains("People expiry: hourly"));
        Timer without = t.run(Map.of("KILNMARK_MODE", "production"), List.of("up", "--no-browser"));
        assertTrue(without.out().contains("Removed the hourly expiry"), without.out());
        assertTrue(without.units().isEmpty() || !without.units().contains("people expire"), without.units());
        assertTrue(t.run(WITH_KEYCLOAK, List.of("check")).out().contains("People expiry: not set up"));
        assertTrue(t.run(WITH_KEYCLOAK, List.of("up", "--no-browser")).out().contains("Set up the hourly expiry"));
        // never a task or timer that runs a script others may change, as the user (Windows) or root (Linux)
        if (t.windows()) {
            // the launcher, then a file a run reads (config\.env), each writable by everyone
            for (String file : List.of("kilnmark.ps1", "config\\.env", "versions\\9.9.9\\containers", "versions\\9.9.9\\containers\\kilnmark-env.sh")) {
                Path planted = t.root().resolve(file);
                icacls(planted, "/grant", "*S-1-1-0:(W)");
                Timer open = t.run(WITH_KEYCLOAK, List.of("up", "--no-browser"));
                assertTrue(open.out().contains("was not set up") && open.out().contains("may be changed by"), file + ": " + open.out());
                List<String> asked = open.schtasks().lines().filter(l -> !l.isBlank()).toList();
                assertFalse(asked.get(asked.size() - 1).startsWith("/Create"), "none made, any it had taken away: " + asked);
                assertFalse(Files.exists(t.root().resolve("schtasks.log.task")), "no task left: " + asked);
                // and the hourly run itself refuses, and says so
                String refused = t.run(WITH_KEYCLOAK, List.of("people", "expire")).out();
                assertTrue(refused.contains("people expire was refused") && refused.contains("may be changed by"), refused);
                icacls(planted, "/remove:g", "*S-1-1-0");
            }
        } else {
            Timer asRoot = t.run(WITH_KEYCLOAK, List.of("up", "--no-browser"), Map.of("KILNMARK_TEST_UID", "0"), "");
            assertTrue(asRoot.out().contains("never as root: sudo -iu ") && !asRoot.out().contains("is ready at"),
                asRoot.out());
            String rootRefused = t.run(WITH_KEYCLOAK, List.of("people", "expire"), Map.of("KILNMARK_TEST_UID", "0"), "").out();
            assertTrue(rootRefused.contains("never as root") && !rootRefused.contains("keycloak-setup:"), rootRefused);
            // a file a run reads (config/.env), or the version's setup script, writable by its group
            // (where chmod means it: Git Bash on Windows keeps NTFS's permissions as they are)
            boolean posix = !System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win");
            for (String file : posix ? List.of("config/.env", "versions/9.9.9/containers/keycloak/setup.sh",
                "versions/9.9.9/containers/kilnmark-env.sh", "versions/9.9.9/containers") : List.<String>of()) {
                Path planted = t.root().resolve(file);
                chmod(planted, "g+w");
                Timer open = t.run(WITH_KEYCLOAK, List.of("up", "--no-browser"));
                assertTrue(open.out().contains("was not set up") && open.out().contains(file + " may be changed by someone other"),
                    file + ": " + open.out());
                String refused = t.run(WITH_KEYCLOAK, List.of("people", "expire")).out();
                assertTrue(refused.contains("people expire was refused") && refused.contains("may be changed by"), refused);
                chmod(planted, "g-w");
            }
        }
        // Windows: set up again, once nobody else may write the tree; Linux: root's refused start left
        // the timer as it was (where chmod means it, the planted files above took it away)
        Timer again2 = t.run(WITH_KEYCLOAK, List.of("up", "--no-browser"));
        boolean posixHere = !System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win");
        assertTrue(again2.out().contains("is ready at") && ((t.windows() || posixHere) ? again2.out().contains("Set up the hourly expiry")
            : again2.units().contains("people expire")), again2.out());
        // a project name the timer could not take is refused at once
        String bad = t.run(WITH_KEYCLOAK, List.of("up", "--no-browser"), Map.of("KILNMARK_PROJECT", "My Kilnmark"), "").out();
        assertTrue(bad.contains("KILNMARK_PROJECT is lower-case letters"), bad);
        // what stops needs no secret: down and uninstall's stop work whatever the secrets hold or lack,
        // and compose reads config/.env without them; a start refuses a secret with $ by its name
        Map<String, String> broken = new LinkedHashMap<>(WITH_KEYCLOAK);
        broken.put("KILNMARK_ADMIN_TOKEN", "");
        broken.put("KILNMARK_KEYCLOAK_ADMIN_PASSWORD", "kc${oops");
        String down = t.run(broken, List.of("down")).out();
        assertTrue(down.contains("Kilnmark is stopped. Its data is kept."), down);
        String composeEnv = Files.readString(t.root().resolve("config/run/services/compose.env"));
        assertTrue(composeEnv.contains("KILNMARK_MODE=production") && !composeEnv.contains("PASSWORD") && !composeEnv.contains("TOKEN="), composeEnv);
        Map<String, String> dollar = new LinkedHashMap<>(WITH_KEYCLOAK);
        dollar.put("KILNMARK_KEYCLOAK_ADMIN_PASSWORD", "kc${oops");
        String upDollar = t.run(dollar, List.of("up", "--no-browser")).out();
        assertTrue(upDollar.contains("KILNMARK_KEYCLOAK_ADMIN_PASSWORD in config") && upDollar.contains("holds $ or `")
            && !upDollar.contains("oops") && !upDollar.contains("is ready at"), upDollar);
        Timer stoppedBroken = t.run(broken, List.of("_stop-for-uninstall"));
        assertTrue(stoppedBroken.out().contains("Kilnmark is stopped and will not start again by itself"), stoppedBroken.out());
        t.run(WITH_KEYCLOAK, List.of("up", "--no-browser"));
        Timer gone = t.run(WITH_KEYCLOAK, List.of("_stop-for-uninstall"));
        assertTrue(gone.out().contains("Removed the hourly expiry"), gone.out());
        assertTrue(gone.units().isEmpty() || !gone.units().contains("people expire"), gone.units());
        if (!gone.schtasks().isEmpty()) {
            assertTrue(gone.schtasks().contains("/Delete /TN \"Kilnmark kilnmark people expire\""), gone.schtasks());
        }
        // purge keeps an image another install's container still uses, and says so
        Timer purged = t.run(WITH_KEYCLOAK, List.of("_stop-for-uninstall", "--purge"), Map.of("FAKE_IMAGE_IN_USE", "kilnmark-server"),
            "DELETE");
        assertTrue(purged.out().contains("Kept the images ghcr.io/jporeilly/kilnmark-server:9.9.9: other containers"),
            purged.out());
        String removed = Files.readString(t.root().resolve("rmi.log"));
        assertTrue(removed.contains("kilnmark-postgres:9.9.9") && !removed.contains("kilnmark-server"), removed);
    }

    static void icacls(Path file, String... args) throws Exception {
        List<String> c = new ArrayList<>(List.of("icacls.exe", file.toString()));
        c.addAll(List.of(args));
        Process p = new ProcessBuilder(c).redirectErrorStream(true).start();
        p.getInputStream().readAllBytes();
        assertEquals(0, p.waitFor(), String.join(" ", c));
    }

    /** chmod, through the shell the Linux edition runs with (Git Bash maps it onto Windows' permissions). */
    static void chmod(Path file, String mode) throws Exception {
        String bash = System.getenv("KILNMARK_TEST_BASH");
        Process p = new ProcessBuilder(bash != null && !bash.isBlank() ? bash : "bash", "-c",
            "chmod " + mode + " '" + file.toString().replace('\\', '/') + "'").redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(0, p.waitFor(), out);
    }

    private static Path uncheckedRoot(boolean windows) {
        try {
            // a space in the path, as C:\Program Files has: the task and the unit quote it
            return installRoot(windows, Files.createTempDirectory("kilnmark timer"));
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    static Map<String, String> withApi(Map<String, String> settings, Api api) {
        Map<String, String> all = new LinkedHashMap<>(settings);
        all.put("KILNMARK_PORT", String.valueOf(api.port()));
        all.put("KILNMARK_KEYCLOAK_PORT", String.valueOf(api.port()));
        return all;
    }

    /** The systemd units the Linux edition wrote, as text. */
    private static String unitsOf(Path root) throws IOException {
        Path dir = root.resolve("systemd");
        if (!Files.isDirectory(dir)) {
            return "";
        }
        StringBuilder b = new StringBuilder();
        try (var files = Files.list(dir)) {
            for (Path f : files.sorted().toList()) {
                b.append(Files.readString(f));
            }
        }
        return b.toString();
    }

    /**
     * The one-offs' secrets go once the run is over; what stays is the services' own files, which
     * every compose command reads (ADR 0051, decision 13), and nothing else in their folder.
     */
    private static void noRunSecretsLeft(Path root) throws IOException {
        Path run = root.resolve("config/run");
        if (Files.isDirectory(run)) {
            try (var left = Files.list(run)) {
                assertEquals(List.of(), left.filter(p -> !p.getFileName().toString().equals("services")).toList(),
                    "the run's secrets are still there");
            }
            Path services = run.resolve("services");
            if (Files.isDirectory(services)) {
                try (var kept = Files.list(services)) {
                    // (compose.env always; the services' files once their secrets were all there to write)
                    List<String> names = kept.map(p -> p.getFileName().toString()).sorted().toList();
                    assertTrue(List.of(List.of("compose.env"), List.of("compose.env", "keycloak-db.env", "keycloak.env", "kilnmark.env", "postgres.env"))
                        .contains(names), "the services' files, and nothing else: " + names);
                }
            }
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
    static Path fakeEngine(boolean windows, boolean ok) throws IOException {
        Path dir = Files.createTempDirectory("kilnmark-fake-engine");
        String exit = ok ? "0" : "1";
        String name = ok ? "engine-ok" : "engine";
        if (windows) {
            Path f = dir.resolve(name + ".cmd");
            Files.writeString(f, "@echo off\r\n"
                + "if defined FAKE_PROVIDER_LOG echo provider [%PODMAN_COMPOSE_PROVIDER%]>> \"%FAKE_PROVIDER_LOG%\"\r\n"
                + "if \"%1 %2\"==\"compose version\" exit /b 0\r\n"
                + "if not defined FAKE_PODMAN_VERSION set FAKE_PODMAN_VERSION=5.4.2\r\n"
                + "if not defined FAKE_CGROUPS set FAKE_CGROUPS=[cpu memory pids]\r\n"
                + "if \"%1 %2\"==\"version --format\" (echo %FAKE_PODMAN_VERSION%& exit /b 0)\r\n"
                + "if not \"%1\"==\"info\" goto notinfo\r\n"
                + "echo %* | %SystemRoot%\\System32\\findstr.exe /c:\"CgroupsVersion\" > nul && (echo v2& exit /b 0)\r\n"
                + "echo %* | %SystemRoot%\\System32\\findstr.exe /c:\"CgroupControllers\" > nul && (echo %FAKE_CGROUPS%& exit /b 0)\r\n"
                + "echo %* | %SystemRoot%\\System32\\findstr.exe /c:\"MemTotal\" > nul && (echo 8589934592& exit /b 0)\r\n"
                + "exit /b 0\r\n"
                + ":notinfo\r\n"
                + "if not defined FAKE_RECORDED_MODE set FAKE_RECORDED_MODE=starter-pack\r\n"
                + "echo %* | %SystemRoot%\\System32\\findstr.exe /c:\"install_id\" > nul && (echo 0e38a49e-ee00-4b1e-98f9-fe3c44e6fa09& exit /b 0)\r\n"
                + "echo %* | %SystemRoot%\\System32\\findstr.exe /c:\"psql\" > nul && if \"%FAKE_RECORDED_MODE%\"==\"none\" exit /b 1\r\n"
                + "echo %* | %SystemRoot%\\System32\\findstr.exe /c:\"psql\" > nul && (echo %FAKE_RECORDED_MODE%& exit /b 0)\r\n"
                + "if not \"%1\"==\"ps\" goto notps\r\n"
                + "echo %* | %SystemRoot%\\System32\\findstr.exe /c:\"ancestor=\" > nul || (echo Exited ^(0^) 2 seconds ago& exit /b 0)\r\n"
                + "if not defined FAKE_IMAGE_IN_USE exit /b 0\r\n"
                + "echo %* | %SystemRoot%\\System32\\findstr.exe /c:\"%FAKE_IMAGE_IN_USE%\" > nul && echo other_kilnmark_1\r\n"
                + "exit /b 0\r\n"
                + ":notps\r\n"
                + "if \"%1\"==\"rmi\" (echo %2>> \"%FAKE_RMI_LOG%\"& exit /b 0)\r\n"
                + "echo %* | %SystemRoot%\\System32\\findstr.exe /c:\" up -d\" > nul && echo install id [%KILNMARK_INSTALL_ID%]>> \"%FAKE_UP_LOG%\"\r\n"
                + "if \"%1\"==\"logs\" (echo keycloak-setup: started& echo keycloak-setup: disabled old@bank.example: their temporary password was not used within 72 hours& exit /b 0)\r\n"
                + "if not defined KILNMARK_RUN_DIR goto nosecrets\r\n"
                + "set sd=%KILNMARK_RUN_DIR:/=\\%\r\n"
                + "for %%I in (\"%sd%\") do set rn=%%~nxI\r\n"
                + "%SystemRoot%\\System32\\findstr.exe /c:\"KILNMARK_KEYCLOAK_ADMIN_PASSWORD='kc-admin-1'\" \"%KILNMARK_ROOT%\\config\\run\\%rn%\\keycloak-setup.env\" > nul 2> nul && %SystemRoot%\\System32\\findstr.exe /c:\"KILNMARK_OIDC_CLIENT_SECRET='bare-secret'\" \"%KILNMARK_ROOT%\\config\\run\\%rn%\\keycloak-setup.env\" > nul 2> nul && echo keycloak-setup: secrets [as compose reads them]\r\n"
                + ":nosecrets\r\n"
                // the services' own files: each its own secrets, as compose hands them to podman (--env-file)
                + "if not defined KILNMARK_SECRETS_DIR goto noservices\r\n"
                + "set sv=%KILNMARK_ROOT%\\config\\run\\services\r\n"
                + "%SystemRoot%\\System32\\findstr.exe /b /c:\"KC_BOOTSTRAP_ADMIN_PASSWORD=kc-admin-1\" \"%sv%\\keycloak.env\" > nul 2> nul"
                + " && %SystemRoot%\\System32\\findstr.exe /b /c:\"QUARKUS_OIDC_CREDENTIALS_SECRET=bare-secret\" \"%sv%\\kilnmark.env\" > nul 2> nul"
                + " && %SystemRoot%\\System32\\findstr.exe /b /c:\"KILNMARK_ADMIN_TOKEN=km_x\" \"%sv%\\kilnmark.env\" > nul 2> nul"
                + " && %SystemRoot%\\System32\\findstr.exe /b /c:\"QUARKUS_DATASOURCE_PASSWORD=db-0123456789\" \"%sv%\\kilnmark.env\" > nul 2> nul"
                + " && %SystemRoot%\\System32\\findstr.exe /b /c:\"POSTGRES_PASSWORD=db-0123456789\" \"%sv%\\postgres.env\" > nul 2> nul"
                + " && (%SystemRoot%\\System32\\findstr.exe /i /c:\"KC_\" /c:\"KEYCLOAK\" \"%sv%\\kilnmark.env\" > nul 2> nul"
                + " || echo services: secrets [as compose reads them])\r\n"
                + ":noservices\r\n"
                + "set pw=\r\n"
                + "set /p pw=\r\n"
                + "echo podman run --name kilnmark_x -e KC_ADMIN_PASSWORD=" + LEAKED + " quay.io/keycloak/keycloak 1>&2\r\n"
                + "echo ['podman', 'run', '-e', 'KILNMARK_OIDC_CLIENT_SECRET=" + LEAKED + "'] 1>&2\r\n"
                + "if defined pw echo keycloak-setup: got a password\r\n"
                + "echo keycloak-setup: seen [%KILNMARK_MODE%] [%KILNMARK_STARTER%]\r\n"
                + "if not defined KILNMARK_PEOPLE_ACTION exit /b " + exit + "\r\n"
                + "echo keycloak-setup: person [%KILNMARK_PEOPLE_ACTION%] [%KILNMARK_PERSON_EMAIL%] [%KILNMARK_PERSON_FIRST_NAME%] [%KILNMARK_PERSON_LAST_NAME%] [%KILNMARK_PERSON_GROUPS%]\r\n"
                + (ok ? "if \"%KILNMARK_PEOPLE_ACTION%\"==\"list\" (echo keycloak-setup: disabled old@bank.example: their temporary password was not used within 72 hours& echo keycloak-setup: person rita@bank.example groups=kilnmark-admins status=active must-choose-password=yes& echo keycloak-setup: people: 1)\r\n"
                    + "if \"%KILNMARK_PEOPLE_ACTION%\"==\"expire\" (for /L %%i in (1,1,1200) do @echo keycloak-setup: disabled p%%i@bank.example: their temporary password was not used within 72 hours)\r\n"
                    + "if \"%KILNMARK_PEOPLE_ACTION%\"==\"expire\" (echo keycloak-setup: people expire: done& exit /b 0)\r\n"
                    + "if \"%KILNMARK_PEOPLE_ACTION%\"==\"enable\" (echo keycloak-setup: enabled %pw%& echo %pw%>> \"%KILNMARK_ROOT%\\enabled.log\"& exit /b 0)\r\n"
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
            + "if [ \"$1 $2\" = \"version --format\" ]; then echo \"${FAKE_PODMAN_VERSION:-5.4.2}\"; exit 0; fi\n"
            + "if [ \"$1\" = info ]; then case \"$*\" in *CgroupsVersion*) echo v2 ;; *CgroupControllers*) echo \"${FAKE_CGROUPS:-[cpu memory pids]}\" ;;\n"
            + "  *MemTotal*) echo 8589934592 ;;\n"
            // the log driver a new container gets (FAKE_LOG_DRIVER); FAKE_BAD_CONF: Podman cannot read the containers.conf
            // given it; FAKE_INFO_FAILS: nor the one it has
            + "  *LogDriver*) [ -z \"${FAKE_INFO_FAILS-}\" ] || exit 125\n"
            + "    [ -z \"${CONTAINERS_CONF-}\" ] || [ -z \"${FAKE_BAD_CONF-}\" ] || exit 125; echo \"${FAKE_LOG_DRIVER-}\" ;; esac; exit 0; fi\n"
            // the log driver the containers there were made with
            + "case \"$1\" in inspect) echo \"${FAKE_CONTAINER_LOG_DRIVER:-k8s-file}\"; exit 0 ;; esac\n"
            + "case \"$*\" in *install_id*) echo 0e38a49e-ee00-4b1e-98f9-fe3c44e6fa09; exit 0 ;; *psql*) [ \"${FAKE_RECORDED_MODE-}\" != none ] || exit 1\n"
            + "  echo \"${FAKE_RECORDED_MODE:-starter-pack}\"; exit 0 ;; esac\n"
            + "case \"$*\" in *\" up -d\"*) echo \"install id [${KILNMARK_INSTALL_ID-}]\" >> \"${FAKE_UP_LOG:-/dev/null}\" ;; esac\n"
            + "case \"$*\" in ps*ancestor=*) case \"$*\" in *\"${FAKE_IMAGE_IN_USE:-no image in use}\"*) echo other_kilnmark_1 ;; esac; exit 0 ;; esac\n"
            + "case \"$1\" in rmi) echo \"$2\" >> \"${FAKE_RMI_LOG:-/dev/null}\"; exit 0 ;; esac\n"
            + "case \"$1\" in ps) echo 'Exited (0) 2 seconds ago'; exit 0 ;; logs) echo 'keycloak-setup: started'\n"
            + "  echo 'keycloak-setup: disabled old@bank.example: their temporary password was not used within 72 hours'; exit 0 ;; esac\n"
            + "f=\"${KILNMARK_ROOT-}/config/run/${KILNMARK_RUN_DIR##*/}/keycloak-setup.env\"\n"
            + "if [ -n \"${KILNMARK_RUN_DIR-}\" ] && [ \"$(cat \"$f\" 2> /dev/null)\" = \"KILNMARK_KEYCLOAK_ADMIN_PASSWORD='kc-admin-1'\n"
            + "KILNMARK_OIDC_CLIENT_SECRET='bare-secret'\" ]; then echo 'keycloak-setup: secrets [as compose reads them]'; fi\n"
            // the services' own files: each its own secrets, as compose hands them to podman (--env-file)
            + "sv=\"${KILNMARK_SECRETS_DIR-}\"\n"
            + "if [ -n \"$sv\" ] && [ \"$(cat \"$sv/kilnmark.env\" 2> /dev/null)\" = \"QUARKUS_DATASOURCE_PASSWORD=db-0123456789\n"
            + "KILNMARK_ADMIN_TOKEN=km_x\nQUARKUS_OIDC_CREDENTIALS_SECRET=bare-secret\" ]"
            + " && [ \"$(cat \"$sv/keycloak.env\" 2> /dev/null)\" = KC_BOOTSTRAP_ADMIN_PASSWORD=kc-admin-1 ]"
            + " && [ \"$(cat \"$sv/postgres.env\" 2> /dev/null)\" = POSTGRES_PASSWORD=db-0123456789 ]; then\n"
            + "  echo 'services: secrets [as compose reads them]'; fi\n"
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
                + "    enable) echo \"keycloak-setup: enabled $pw\"; echo \"$pw\" >> \"${KILNMARK_ROOT-}/enabled.log\" ;;\n"
                + "    expire) for i in $(seq 1 1200); do echo \"keycloak-setup: disabled p$i@bank.example: their temporary password was not used within 72 hours\"; done\n"
                + "      echo 'keycloak-setup: people expire: done' ;;\n"
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

    static final Map<Boolean, Path> TIMER_TOOLS = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * A systemctl (false) or schtasks (true) that only notes what it was asked, in FAKE_TIMER_LOG:
     * systemctl says a timer is enabled when its unit file is in KILNMARK_SYSTEMD_DIR; schtasks has a
     * task once created, until deleted.
     */
    private static Path fakeTimerTool(boolean windows) {
        return TIMER_TOOLS.computeIfAbsent(windows, w -> {
            try {
                Path dir = Files.createTempDirectory("kilnmark-fake-timer");
                if (w) {
                    Path f = dir.resolve("schtasks.cmd");
                    Files.writeString(f, "@echo off\r\n"
                        + "echo %*>> \"%FAKE_TIMER_LOG%\"\r\n"
                        + "if /i \"%1\"==\"/Query\" (if exist \"%FAKE_TIMER_LOG%.task\" (exit /b 0) else (exit /b 1))\r\n"
                        + "if /i \"%1\"==\"/Create\" (echo task> \"%FAKE_TIMER_LOG%.task\"& exit /b 0)\r\n"
                        + "if /i \"%1\"==\"/Delete\" (del \"%FAKE_TIMER_LOG%.task\"& exit /b 0)\r\n"
                        + "exit /b 0\r\n");
                    return f;
                }
                Path f = dir.resolve("systemctl.sh");
                Files.writeString(f, "#!/bin/bash\n"
                    + "echo \"$*\" >> \"$FAKE_TIMER_LOG\"\n"
                    + "case \"$*\" in *is-enabled*) [ -f \"$KILNMARK_SYSTEMD_DIR/${@: -1}\" ] && echo enabled && exit 0; exit 1 ;; esac\n"
                    + "exit 0\n");
                try {
                    Files.setPosixFilePermissions(f, java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"));
                } catch (UnsupportedOperationException e) {
                    // Windows: Git Bash runs it by its first line
                }
                return f;
            } catch (IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
        });
    }

    /** An install root with one version in it, as install leaves it; Windows marks it in current.txt. */
    static Path installRoot(boolean windows) throws IOException {
        return installRoot(windows, null);
    }

    /** As {@link #installRoot(boolean)}, in {@code parent} (null: the temporary folder). */
    static Path installRoot(boolean windows, Path parent) throws IOException {
        Path root = parent == null ? Files.createTempDirectory("kilnmark-starter-test")
            : Files.createTempDirectory(parent, "kilnmark-starter-test");
        Path v = Files.createDirectories(root.resolve("versions/9.9.9"));
        Files.writeString(v.resolve("compose.yaml"), "services:\n  kilnmark:\n    image: ghcr.io/jporeilly/kilnmark-server:9.9.9\n");
        Files.createDirectories(root.resolve("config"));
        Files.createDirectories(root.resolve("logs"));
        // what an hourly run uses, as install leaves it: the launcher at the root, setup's script
        Files.createDirectories(v.resolve("containers/keycloak"));
        Files.writeString(v.resolve("containers/keycloak/setup.sh"), "# setup\n");
        Files.writeString(v.resolve("containers/kilnmark-env.sh"), "# the services' start\n");
        Files.writeString(root.resolve("config/.env"), "");
        // the program folder names its data folder (install writes it): here, the same folder
        Files.writeString(root.resolve("data-folder.txt"), root.toString() + "\n");
        if (windows) {
            Files.writeString(root.resolve("current.txt"), "9.9.9\n");
            Files.writeString(root.resolve("kilnmark.ps1"), "# the launcher\r\n");
        } else {
            link(root.resolve("current"), v);
            Files.writeString(root.resolve("kilnmark.sh"), "# the launcher\n");
        }
        return root;
    }

    /**
     * {@code link} leads to the folder {@code to}: a symbolic link, or on Windows, where one needs a
     * privilege, a junction, which Git Bash takes for one.
     */
    static void link(Path link, Path to) throws IOException {
        try {
            Files.createSymbolicLink(link, to);
        } catch (IOException | UnsupportedOperationException e) {
            try {
                Process p = new ProcessBuilder("cmd.exe", "/c", "mklink", "/J", link.toString(), to.toAbsolutePath().toString())
                    .redirectErrorStream(true).start();
                p.getInputStream().readAllBytes();
                assertEquals(0, p.waitFor(), "mklink /J " + link);
            } catch (InterruptedException i) {
                Thread.currentThread().interrupt();
                throw new IOException(i);
            }
        }
    }

    /** Whether everyone may write {@code p} (Windows: an entry that applies to it, not only to what is in it). */
    static boolean everyoneWrites(Path p) throws IOException {
        var view = Files.getFileAttributeView(p, java.nio.file.attribute.AclFileAttributeView.class);
        for (var e : view.getAcl()) {
            if (e.type() == java.nio.file.attribute.AclEntryType.ALLOW && e.principal().getName().contains("Everyone")
                && !e.flags().contains(java.nio.file.attribute.AclEntryFlag.INHERIT_ONLY)
                && (e.permissions().contains(java.nio.file.attribute.AclEntryPermission.WRITE_DATA)
                    || e.permissions().contains(java.nio.file.attribute.AclEntryPermission.APPEND_DATA))) {
                return true;
            }
        }
        return false;
    }

    /** Ports nothing listens on, for install's checks. */
    static Map<String, String> freePorts() throws IOException {
        Map<String, String> m = new LinkedHashMap<>();
        for (String k : List.of("KILNMARK_PORT", "KILNMARK_HTTPS_PORT", "KILNMARK_DB_PORT")) {
            try (var socket = new java.net.ServerSocket(0)) {
                m.put(k, String.valueOf(socket.getLocalPort()));
            }
        }
        return m;
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
    static void writeEnv(Path root, Map<String, String> settings) throws IOException {
        StringBuilder b = new StringBuilder("KILNMARK_DB_PASSWORD=db-0123456789\nKILNMARK_ADMIN_TOKEN=km_x\n");
        if (settings.keySet().stream().noneMatch(k -> k.endsWith("KILNMARK_MODE"))) {
            b.append("KILNMARK_MODE=starter-pack\n");
        }
        settings.forEach((k, v) -> b.append(k).append('=').append(v).append('\n'));
        Files.writeString(root.resolve("config/.env"), b.toString());
    }

    static Map<String, String> readEnv(Path root) throws IOException {
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
    static String run(List<String> command, Path root, String engine) throws Exception {
        return run(command, root, engine, Map.of());
    }

    /** As {@link #run(List, Path, String)}, with these in the script's own environment too. */
    static String run(List<String> command, Path root, String engine, Map<String, String> outside)
        throws Exception {
        ProcessBuilder pb = new ProcessBuilder(new ArrayList<>(command)).redirectErrorStream(true);
        pb.environment().put("KILNMARK_ROOT", root.toString());
        pb.environment().put("KILNMARK_ENGINE", engine);
        pb.environment().remove("KILNMARK_PROJECT");
        // the hourly expiry's tools: fakes that keep to this root, never the machine's own
        pb.environment().put("KILNMARK_SYSTEMCTL", fakeTimerTool(false).toString().replace('\\', '/'));
        pb.environment().put("KILNMARK_SYSTEMD_DIR", root.resolve("systemd").toString().replace('\\', '/'));
        pb.environment().put("KILNMARK_SCHTASKS", fakeTimerTool(true).toString());
        // an account of its own, as Kilnmark runs as, even where the tests run as root (CI's containers);
        // the test hooks count only with their marker, which no release sets
        pb.environment().put("KILNMARK_TEST_UID", "1000");
        pb.environment().put("KILNMARK_TEST_HOOKS", "only-in-kilnmark-tests");
        pb.environment().put("FAKE_TIMER_LOG", root.resolve(System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT)
            .contains("win") ? "schtasks.log" : "systemctl.log").toString());
        pb.environment().put("FAKE_RMI_LOG", root.resolve("rmi.log").toString());
        pb.environment().put("FAKE_UP_LOG", root.resolve("up.log").toString());
        // a null removes a setting the tests' own environment has
        outside.forEach((k, val) -> {
            if (val == null) {
                pb.environment().remove(k);
            } else {
                pb.environment().put(k, val);
            }
        });
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

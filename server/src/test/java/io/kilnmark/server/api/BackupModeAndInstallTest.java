package io.kilnmark.server.api;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/**
 * A backup names its install's deployment mode and own id (ADR 0051), and a restore, in both
 * editions, takes it only where they agree with its dump, its Keycloak database is that install's
 * (the id and mode its realm carries) and, for a production install, holds neither the Starter
 * Pack's group nor its example people: each checked before anything is stopped or replaced.
 *
 * <p>The scripts are run for real, with a compose (Linux) or an engine (Windows) that answers the
 * databases' questions as the test says and does nothing else. A restore that passes every check
 * goes on to stop Kilnmark, where the fake says it still runs: the restore stops there, having
 * replaced nothing.
 */
class BackupModeAndInstallTest {

    static final String ID = "0e38a49e-ee00-4b1e-98f9-fe3c44e6fa09";
    static final String OTHER_ID = "7d2c1b0a-9f8e-4d7c-8b6a-5f4e3d2c1b0a";
    static final String PASSED = "still running after the stop";

    /** What the databases answer: the backup's dump, the live install, and the backup's Keycloak database ("-": nothing). */
    record Answers(String backupMode, String backupId, String liveMode, String kcId, String kcMode, String kcPack,
        String kcExamples) {
        Map<String, String> env() {
            Map<String, String> e = new LinkedHashMap<>();
            e.put("FAKE_BACKUP_MODE", backupMode);
            e.put("FAKE_BACKUP_ID", backupId);
            e.put("FAKE_LIVE_MODE", liveMode);
            e.put("FAKE_LIVE_ID", backupId);
            e.put("FAKE_KC_ID", kcId);
            e.put("FAKE_KC_MODE", kcMode);
            e.put("FAKE_KC_PACK", kcPack);
            e.put("FAKE_KC_EXAMPLES", kcExamples);
            return e;
        }
    }

    interface Restore {
        /** Restores a backup whose backup.txt says {@code fileMode} and {@code fileId} ("-": not at all) into an install of {@code mode}. */
        String run(String mode, String fileMode, String fileId, Answers a) throws Exception;
    }

    private static void restoreRules(Restore r) throws Exception {
        Answers good = new Answers("production", ID, "production", ID, "production", "0", "0");
        String ok = r.run("production", "production", ID, good);
        assertTrue(ok.contains(PASSED), ok);
        // Keycloak's database with no install recorded on its realm yet: by the group and people alone
        assertTrue(r.run("production", "production", ID, new Answers("production", ID, "production", "-", "-", "0", "0"))
            .contains(PASSED));
        // backup.txt from before the mode and id were recorded, or naming another install's
        for (String[] file : new String[][] {{"-", ID}, {"production", "-"}}) {
            String out = r.run("production", file[0], file[1], good);
            assertTrue(out.contains("does not say which install it is of") && !out.contains(PASSED), out);
        }
        String other = r.run("production", "production", OTHER_ID, good);
        assertTrue(other.contains("does not match its database") && !other.contains(PASSED), other);
        assertTrue(r.run("production", "starter-pack", ID, good).contains("does not match its database"));
        // a dump of the other mode, whatever backup.txt says
        String mode = r.run("production", "starter-pack", ID, new Answers("starter-pack", ID, "production", ID,
            "starter-pack", "1", "5"));
        assertTrue(mode.contains("is of a starter-pack install") && !mode.contains(PASSED), mode);
        // Keycloak's database of another install, or of the other mode
        for (Answers a : List.of(new Answers("production", ID, "production", OTHER_ID, "production", "0", "0"),
            new Answers("production", ID, "production", ID, "starter-pack", "0", "0"))) {
            String out = r.run("production", "production", ID, a);
            assertTrue(out.contains("belongs to another Kilnmark install") && !out.contains(PASSED), out);
        }
        // on production, never a Keycloak database with the Starter Pack's group or example people
        for (Answers a : List.of(new Answers("production", ID, "production", "-", "-", "1", "0"),
            new Answers("production", ID, "production", ID, "production", "0", "2"))) {
            String out = r.run("production", "production", ID, a);
            assertTrue(out.contains("has the Starter Pack's group or example people") && !out.contains(PASSED), out);
        }
        // a Starter Pack install's backup has them, and goes back into one
        String pack = r.run("starter-pack", "starter-pack", ID, new Answers("starter-pack", ID, "starter-pack", ID,
            "starter-pack", "1", "5"));
        assertTrue(pack.contains(PASSED), pack);
    }

    @Test
    void theLinuxEditionRestoresOnlyAWholeBackupOfThisInstallsMode() throws Exception {
        String bash = bash();
        Path fake = fakeCompose();
        restoreRules((mode, fileMode, fileId, a) -> {
            Path release = release(mode);
            Path backup = backup(fileMode, fileId);
            Map<String, String> env = a.env();
            env.put("KILNMARK_COMPOSE", fake.toString().replace('\\', '/'));
            env.put("KILNMARK_KEYCLOAK", "on");
            env.put("KILNMARK_EXPECTED_MODE", mode);
            return run(List.of(bash, Path.of("../containers/restore.sh").toAbsolutePath().toString().replace('\\', '/'),
                backup.toString().replace('\\', '/'), "--yes"), release, env);
        });
    }

    @Test
    void theLinuxBackupNamesItsInstallsModeAndId() throws Exception {
        String bash = bash();
        Path fake = fakeCompose();
        Path release = release("production");
        // beside the release, by a relative path: Git Bash's tar takes "C:" for a host
        Path dest = Files.createDirectories(release.resolve("backups"));
        Map<String, String> env = new Answers("production", ID, "production", "-", "-", "0", "0").env();
        env.put("KILNMARK_COMPOSE", fake.toString().replace('\\', '/'));
        env.put("KILNMARK_KEYCLOAK", "on");
        String script = Path.of("../containers/backup.sh").toAbsolutePath().toString().replace('\\', '/');
        String out = run(List.of(bash, script, "backups"), release, env);
        assertTrue(out.contains("Backed up Kilnmark 9.9.9"), out);
        Path made;
        try (var dirs = Files.list(dest)) {
            made = dirs.findFirst().orElseThrow();
        }
        String info = Files.readString(made.resolve("backup.txt"));
        assertTrue(info.contains("deployment_mode=production\n") && info.contains("install_id=" + ID + "\n"), info);
        // a database with no id recorded yet is not backed up as if it had one
        env.put("FAKE_LIVE_ID", "-");
        Thread.sleep(1100);
        String none = run(List.of(bash, script, "backups"), release, env);
        assertTrue(none.contains("Backup failed: the database has no deployment mode or install id recorded"), none);
        // the Windows edition writes the same two lines
        String ps = Files.readString(ControlScriptStarterTest.LAUNCH.resolve("kilnmark.ps1"));
        assertTrue(ps.contains("('deployment_mode=' + $mode), ('install_id=' + $installId))"), "backup.txt in both editions");
    }

    @Test
    void theWindowsEditionRestoresOnlyAWholeBackupOfThisInstallsMode() throws Exception {
        String powershell = System.getenv("KILNMARK_TEST_POWERSHELL");
        Assumptions.assumeTrue(powershell != null && !powershell.isBlank(),
            "set KILNMARK_TEST_POWERSHELL to powershell.exe to run this");
        Path engine = fakeEngine();
        restoreRules((mode, fileMode, fileId, a) -> {
            Path root = Files.createTempDirectory("kilnmark-restore-test");
            Path v = Files.createDirectories(root.resolve("versions/9.9.9"));
            Files.writeString(v.resolve("compose.yaml"), "services:\n  kilnmark:\n    image: ghcr.io/jporeilly/kilnmark-server:9.9.9\n");
            Files.writeString(root.resolve("current.txt"), "9.9.9\n");
            Files.createDirectories(root.resolve("config"));
            Files.writeString(root.resolve("config/.env"), "KILNMARK_MODE=" + mode + "\nCOMPOSE_PROFILES=auth\n");
            Path backup = backup(fileMode, fileId);
            Map<String, String> env = a.env();
            env.put("KILNMARK_ROOT", root.toString());
            env.put("KILNMARK_ENGINE", engine.toString());
            return run(List.of(powershell, "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass", "-File",
                ControlScriptStarterTest.LAUNCH.resolve("kilnmark.ps1").toAbsolutePath().toString(), "restore",
                backup.toString(), "-Yes"), root, env);
        });
    }

    private static String bash() {
        String bash = System.getenv("KILNMARK_TEST_BASH");
        boolean windows = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win");
        String shell = bash != null && !bash.isBlank() ? bash : windows ? null : "bash";
        Assumptions.assumeTrue(shell != null, "set KILNMARK_TEST_BASH to bash to run this on Windows");
        return shell;
    }

    /** A release folder, as restore.sh and backup.sh run beside its compose.yaml and .env. */
    private static Path release(String mode) throws IOException {
        Path dir = Files.createTempDirectory("kilnmark-release");
        Files.writeString(dir.resolve("compose.yaml"), "services:\n  kilnmark:\n    image: ghcr.io/jporeilly/kilnmark-server:9.9.9\n");
        Files.writeString(dir.resolve(".env"), "KILNMARK_MODE=" + mode + "\nCOMPOSE_PROFILES=auth\n");
        return dir;
    }

    /** A whole backup folder, its checksums right, its backup.txt naming this mode and id ("-": not at all). */
    private static Path backup(String mode, String id) throws Exception {
        Path dir = Files.createTempDirectory("kilnmark-backup");
        Files.writeString(dir.resolve("kilnmark-db.dump"), "dump");
        Files.writeString(dir.resolve("kilnmark-keys.tar"), "keys");
        Files.writeString(dir.resolve("keycloak-db.dump"), "keycloak");
        StringBuilder info = new StringBuilder("taken_at=2026-11-07T10:00:00Z\nkilnmark_version=9.9.9\nmigration=23\nkeycloak=yes\n");
        if (!"-".equals(mode)) {
            info.append("deployment_mode=").append(mode).append('\n');
        }
        if (!"-".equals(id)) {
            info.append("install_id=").append(id).append('\n');
        }
        Files.writeString(dir.resolve("backup.txt"), info.toString());
        StringBuilder sums = new StringBuilder();
        for (String f : List.of("keycloak-db.dump", "kilnmark-db.dump", "kilnmark-keys.tar", "backup.txt")) {
            sums.append(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(dir.resolve(f)))))
                .append("  ").append(f).append('\n');
        }
        Files.writeString(dir.resolve("SHA256SUMS"), sums.toString());
        return dir;
    }

    /** compose for restore.sh and backup.sh: the databases' answers from FAKE_*, "-" for none; anything else works. */
    private static Path fakeCompose() throws IOException {
        Path f = Files.createTempDirectory("kilnmark-fake-compose").resolve("compose.sh");
        Files.writeString(f, "#!/bin/bash\n"
            + "say() { [ \"$1\" = - ] || echo \"$1\"; exit 0; }\n"
            + "case \"$*\" in\n"
            + "  *keycloak_restoring*kilnmark_install_id*) say \"$FAKE_KC_ID\" ;;\n"
            + "  *keycloak_restoring*\"'kilnmark_mode'\"*) say \"$FAKE_KC_MODE\" ;;\n"
            + "  *keycloak_restoring*group_attribute*) say \"$FAKE_KC_PACK\" ;;\n"
            + "  *keycloak_restoring*user_entity*) say \"$FAKE_KC_EXAMPLES\" ;;\n"
            + "  *\"-d kilnmark_restoring\"*deployment_mode*) say \"$FAKE_BACKUP_MODE\" ;;\n"
            + "  *\"-d kilnmark_restoring\"*install_id*) say \"$FAKE_BACKUP_ID\" ;;\n"
            + "  *\"-d kilnmark \"*deployment_mode*) say \"$FAKE_LIVE_MODE\" ;;\n"
            + "  *\"-d kilnmark \"*install_id*) say \"$FAKE_LIVE_ID\" ;;\n"
            + "  *flyway_schema_history*) say 23 ;;\n"
            + "  *pg_dump*) say dump-bytes ;;\n"
            + "  *\"tar -C /var/lib/kilnmark\"*) d=$(mktemp -d); echo k > \"$d/secret.key\"; tar -C \"$d\" -cf - .; rm -rf \"$d\"; exit 0 ;;\n"
            + "esac\n"
            + "exit 0\n");
        executable(f);
        return f;
    }

    /** The engine for kilnmark.ps1's restore, as fakeCompose, by findstr over its arguments. */
    private static Path fakeEngine() throws IOException {
        Path f = Files.createTempDirectory("kilnmark-fake-engine").resolve("engine.cmd");
        String find = "%SystemRoot%\\System32\\findstr.exe";
        StringBuilder b = new StringBuilder("@echo off\r\n"
            + "if \"%1 %2\"==\"compose version\" exit /b 0\r\n"
            + "if \"%1\"==\"ps\" (echo fakeid& exit /b 0)\r\n");
        String[][] answers = {{"keycloak_restoring", "kilnmark_install_id", "FAKE_KC_ID"},
            {"keycloak_restoring", "'kilnmark_mode'", "FAKE_KC_MODE"}, {"keycloak_restoring", "group_attribute", "FAKE_KC_PACK"},
            {"keycloak_restoring", "user_entity", "FAKE_KC_EXAMPLES"}, {"-d kilnmark_restoring", "deployment_mode", "FAKE_BACKUP_MODE"},
            {"-d kilnmark_restoring", "install_id", "FAKE_BACKUP_ID"}, {"-d kilnmark ", "deployment_mode", "FAKE_LIVE_MODE"},
            {"-d kilnmark ", "install_id", "FAKE_LIVE_ID"}};
        for (String[] a : answers) {
            b.append("echo %* | ").append(find).append(" /c:\"").append(a[0]).append("\" | ").append(find).append(" /c:\"")
                .append(a[1]).append("\" > nul && goto ").append(a[2]).append("\r\n");
        }
        b.append("exit /b 0\r\n");
        for (String[] a : answers) {
            b.append(':').append(a[2]).append("\r\nif not \"%").append(a[2]).append("%\"==\"-\" echo %").append(a[2])
                .append("%\r\nexit /b 0\r\n");
        }
        Files.writeString(f, b.toString());
        return f;
    }

    private static void executable(Path f) throws IOException {
        try {
            Files.setPosixFilePermissions(f, java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"));
        } catch (UnsupportedOperationException e) {
            // Windows: Git Bash runs it by its first line
        }
    }

    private static String run(List<String> command, Path dir, Map<String, String> env) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(new ArrayList<>(command)).directory(dir.toFile()).redirectErrorStream(true);
        pb.environment().remove("KILNMARK_PROJECT");
        pb.environment().putAll(env);
        Process p = pb.start();
        p.getOutputStream().close();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(p.waitFor(120, TimeUnit.SECONDS), "it stopped");
        assertFalse(out.contains("Restored Kilnmark"), "nothing was replaced: " + out);
        return out;
    }
}

package io.kilnmark.server.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * The run script's files (docs/guide/external-schedulers.md): both editions are in
 * containers/launch, which the release copies to its root beside kilnmark.sh (build.gradle.kts,
 * assembleRelease); the PowerShell and cmd files are ASCII only, since Windows PowerShell 5.1
 * misreads anything else; the shell edition is POSIX sh with LF line ends; and neither takes the
 * token from the command line or an environment value.
 */
class RunScriptFilesTest {

    static final Path LAUNCH = Path.of("../containers/launch");

    @Test
    void bothEditionsAreWhereTheReleasePicksThemUp() {
        for (String f : List.of("kilnmark-run.sh", "kilnmark-run.ps1", "kilnmark-run.cmd")) {
            assertTrue(Files.isRegularFile(LAUNCH.resolve(f)), f);
        }
    }

    @Test
    void theWindowsFilesAreAscii() throws IOException {
        for (String f : List.of("kilnmark-run.ps1", "kilnmark-run.cmd")) {
            byte[] bytes = Files.readAllBytes(LAUNCH.resolve(f));
            List<Integer> at = new ArrayList<>();
            for (int i = 0; i < bytes.length; i++) {
                if (bytes[i] < 0) {
                    at.add(i);
                }
            }
            assertEquals(List.of(), at, f + " has bytes beyond ASCII at these offsets");
        }
    }

    @Test
    void theShellEditionIsPosixShWithLfLineEnds() throws IOException {
        String text = Files.readString(LAUNCH.resolve("kilnmark-run.sh"));
        assertTrue(text.startsWith("#!/bin/sh\n"), "starts #!/bin/sh");
        assertTrue(!text.contains("\r"), "LF line ends only");
        for (String bashOnly : List.of("[[ ", "local ", "declare ", "function ", "jq ", "python")) {
            assertTrue(!text.contains(bashOnly), "uses " + bashOnly.strip());
        }
    }

    @Test
    void neitherEditionTakesTheTokenFromTheCommandLineOrAnEnvironmentValue() throws IOException {
        for (String f : List.of("kilnmark-run.sh", "kilnmark-run.ps1")) {
            String text = Files.readString(LAUNCH.resolve(f));
            assertTrue(text.contains("KILNMARK_TOKEN_FILE"), f);
            assertTrue(!text.contains("KILNMARK_TOKEN}") && !text.contains("$env:KILNMARK_TOKEN\n")
                && !text.contains("--token"), f + " reads the token only from the file");
        }
    }
}

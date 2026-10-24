package io.kilnmark.kiln.plugins.files;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.kilnmark.kiln.connect.ConnectorContext;
import io.kilnmark.kiln.connect.ConnectorException;
import io.kilnmark.kiln.connect.Connectors;
import io.kilnmark.kiln.connect.SourceConfig;

class LocalFileStoreTest {

    @TempDir
    Path tmp;

    Path share;
    Path outside;

    @BeforeEach
    void layout() throws IOException {
        share = Files.createDirectories(tmp.resolve("share"));
        outside = Files.createDirectories(tmp.resolve("secret"));
        Files.writeString(outside.resolve("passwords.csv"), "user,password\nroot,hunter2\n");
        Files.createDirectories(share.resolve("exports/2025"));
        Files.writeString(share.resolve("top.csv"), "a\n1\n");
        Files.writeString(share.resolve("exports/2025/orders.csv"), "a\n1\n");
        Files.writeString(share.resolve("exports/notes.md"), "# notes\n");
        Files.createDirectories(share.resolve(".git"));
        Files.writeString(share.resolve(".git/config"), "x");
        Files.writeString(share.resolve(".hidden.csv"), "a\n1\n");
        Files.createSymbolicLink(share.resolve("escape.csv"), outside.resolve("passwords.csv"));
        Files.createSymbolicLink(share.resolve("escape-dir"), outside);
        Files.createSymbolicLink(share.resolve("inside-link.csv"), share.resolve("top.csv"));
    }

    private static List<String> paths(LocalFileStore s) throws IOException {
        return s.list().stream().map(FileStore.Entry::path).toList();
    }

    @Test
    void listsEverythingInsideAndNothingOutside() throws IOException {
        try (LocalFileStore s = new LocalFileStore(share, null, true)) {
            assertEquals(List.of("exports/2025/orders.csv", "exports/notes.md", "inside-link.csv", "top.csv"),
                paths(s), "hidden files and links leading out of the folder are skipped");
        }
    }

    @Test
    void includeMatchesNamesOrPaths() throws IOException {
        try (LocalFileStore s = new LocalFileStore(share, "*.csv", true)) {
            assertEquals(List.of("exports/2025/orders.csv", "inside-link.csv", "top.csv"), paths(s));
        }
        try (LocalFileStore s = new LocalFileStore(share, "exports/**", true)) {
            assertEquals(List.of("exports/2025/orders.csv", "exports/notes.md"), paths(s));
        }
        try (LocalFileStore s = new LocalFileStore(share, null, false)) {
            assertEquals(List.of("inside-link.csv", "top.csv"), paths(s));
        }
    }

    @Test
    void refusesPathsThatLeadOutside() throws IOException {
        try (LocalFileStore s = new LocalFileStore(share, null, true)) {
            for (String bad : List.of("../secret/passwords.csv", "exports/../../secret/passwords.csv",
                outside.resolve("passwords.csv").toString(), "escape.csv", "escape-dir/passwords.csv", "")) {
                IOException e = assertThrows(IOException.class, () -> s.open(bad), bad);
                assertTrue(e.getMessage().startsWith("Refusing to read"), e.getMessage());
            }
            assertEquals("a\n1\n", new String(s.head("inside-link.csv", 100)));
            IOException gone = assertThrows(IOException.class, () -> s.open("missing.csv"));
            assertTrue(gone.getMessage().contains("scan the source again"), gone.getMessage());
        }
    }

    @Test
    void aMissingOrRelativeFolderIsExplained() {
        IOException e = assertThrows(IOException.class, () -> new LocalFileStore(tmp.resolve("nope"), null, true));
        assertTrue(e.getMessage().contains("does not exist"), e.getMessage());
        e = assertThrows(IOException.class, () -> new LocalFileStore(Path.of("relative"), null, true));
        assertTrue(e.getMessage().contains("absolute"), e.getMessage());
    }

    @Test
    void validationNamesTheSettingAtFault() {
        FilesConnector files = TestFiles.files(tmp);
        assertTrue(files.validate(new SourceConfig("f", "files", Map.of("path", share.toString()), null)).isEmpty());
        assertTrue(files.validate(new SourceConfig("f", "files", Map.of(), null)).get(0).startsWith("path:"));
        assertTrue(files.validate(new SourceConfig("f", "files", Map.of("path", "share"), null)).get(0)
            .startsWith("path:"));
        assertTrue(files.validate(new SourceConfig("f", "files", Map.of("path", share.toString(), "include", "*.{csv"),
            null)).get(0).startsWith("include:"));
        assertTrue(files.validate(new SourceConfig("f", "files", Map.of("path", share.toString(), "recursive", "yes"),
            null)).get(0).startsWith("recursive:"));

        S3Connector s3 = new S3Connector();
        Map<String, String> keyed = Map.of("bucket", "lab-raw", "access_key_id", "AK");
        assertTrue(s3.validate(new SourceConfig("b", "s3", keyed, "secret")).isEmpty());
        assertTrue(s3.validate(new SourceConfig("b", "s3", Map.of(), null)).get(0).startsWith("bucket:"));
        assertTrue(s3.validate(new SourceConfig("b", "s3", Map.of("bucket", "b", "access_key_id", "AK",
            "endpoint", "localhost:9000"), "s")).get(0).startsWith("endpoint:"));
        assertTrue(s3.validate(new SourceConfig("b", "s3", Map.of("bucket", "b", "access_key_id", "AK"), null))
            .get(0).startsWith("access_key_id:"));
        assertTrue(s3.validate(new SourceConfig("b", "s3", Map.of("bucket", "b", "access_key_id", "AK",
            "path_style", "true", "endpoint", "http://localhost:9000"), "secret")).isEmpty());
        assertTrue(!S3Connector.settings(new SourceConfig("b", "s3", Map.of("bucket", "b"), "hunter2")).toString()
            .contains("hunter2"));
    }

    @Test
    void folderSourcesAreOffUntilTheServerAllowsFolders() {
        FilesConnector none = new FilesConnector();
        SourceConfig s = new SourceConfig("f", "files", Map.of("path", share.toString()), null);
        String problem = none.validate(s).get(0);
        assertTrue(problem.startsWith("path:") && problem.contains("kilnmark.files.roots"), problem);
        ConnectorException e = assertThrows(ConnectorException.class, () -> none.catalog(s));
        assertTrue(e.getMessage().contains("kilnmark.files.roots"), e.getMessage());
        assertThrows(IllegalArgumentException.class, () -> new FilesConnector().withContext(new ConnectorContext(
            Map.of(FilesConnector.ROOTS, "relative/folder"))));

        // the registry hands the server's settings to each connector
        FilesConnector loaded = (FilesConnector) Connectors.load(new ConnectorContext(Map.of(FilesConnector.ROOTS,
            share + "," + outside))).get("files").orElseThrow();
        assertEquals(List.of(share, outside), loaded.roots());
        assertTrue(loaded.validate(s).isEmpty());
    }

    @Test
    void onlyFoldersInsideTheAllowedRootsAreRead() throws Exception {
        FilesConnector files = TestFiles.files(share);
        SourceConfig secret = new SourceConfig("s", "files", Map.of("path", outside.toString()), null);
        assertTrue(files.validate(secret).get(0).contains("not inside a folder this server allows"),
            files.validate(secret).get(0));
        SourceConfig dotdot = new SourceConfig("s", "files", Map.of("path", share + "/../secret"), null);
        assertFalse(files.validate(dotdot).isEmpty(), "'..' is resolved before the check");

        // a link inside the root that leads out is refused when validated and again when opened
        SourceConfig viaLink = new SourceConfig("s", "files", Map.of("path", share.resolve("escape-dir").toString()),
            null);
        assertTrue(files.validate(viaLink).get(0).contains("outside the folders this server allows"),
            files.validate(viaLink).get(0));
        IOException e = assertThrows(IOException.class, () -> files.open(viaLink).close());
        assertTrue(e.getMessage().contains("outside the folders this server allows"), e.getMessage());
    }

    @Test
    void s3SourcesNeedTheirOwnKeyUnlessTheServerAllowsItsOwnOnAws() throws Exception {
        S3Connector strict = new S3Connector();
        S3Connector lenient = (S3Connector) new S3Connector().withContext(new ConnectorContext(
            Map.of(S3Connector.ALLOW_DEFAULT_CREDENTIALS, "true")));
        SourceConfig aws = new SourceConfig("b", "s3", Map.of("bucket", "b"), null);
        SourceConfig other = new SourceConfig("b", "s3", Map.of("bucket", "b", "endpoint", "http://storage:9000"), null);
        assertTrue(strict.validate(aws).get(0).startsWith("access_key_id:"));
        assertTrue(lenient.validate(aws).isEmpty());
        assertTrue(lenient.validate(other).get(0).startsWith("access_key_id:"), "never the server's identity elsewhere");
        IOException refused = assertThrows(IOException.class, () -> strict.open(aws));
        assertTrue(refused.getMessage().contains("does not let sources use its own AWS credentials"),
            refused.getMessage());
        assertThrows(IOException.class, () -> lenient.open(other));

        SourceConfig userInfo = new SourceConfig("b", "s3", Map.of("bucket", "b", "access_key_id", "AK",
            "endpoint", "http://admin:pw@storage:9000"), "s");
        assertTrue(strict.validate(userInfo).get(0).contains("must not hold a user name or password"));
        assertThrows(IOException.class, () -> strict.open(userInfo));
    }
}

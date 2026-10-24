package io.kilnmark.kiln.plugins.files;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

import io.kilnmark.kiln.connect.AssetInfo;
import io.kilnmark.kiln.connect.ConnectorException;
import io.kilnmark.kiln.connect.SourceConfig;

/**
 * The S3 connector against VersityGW (Apache-2.0), an S3 gateway that serves a folder: each
 * top-level folder under {@code /data} is a bucket. The bucket holds the reference lab's files,
 * the generated test files under {@code gen/}, and the lab's complaint letter as a document.
 */
class S3IngestTest {

    static final String BUCKET = "lab-raw";
    static final String KEY_ID = "kilnmark-test";
    static final String SECRET = "kilnmark-test-secret";

    @TempDir
    static Path generated;

    static GenericContainer<?> gateway;
    static final S3Connector S3 = new S3Connector();
    static SourceConfig source;
    static Map<String, AssetInfo> assets;

    @BeforeAll
    static void start() throws Exception {
        TestFiles.write(generated);
        gateway = new GenericContainer<>(DockerImageName.parse("ghcr.io/versity/versitygw:v1.10.0"))
            .withEnv("ROOT_ACCESS_KEY", KEY_ID)
            .withEnv("ROOT_SECRET_KEY", SECRET)
            .withCommand("posix", "/data")
            .withExposedPorts(7070)
            .waitingFor(Wait.forListeningPort());
        Path bucket = TestFiles.lab().resolve("bucket");
        copyTree(bucket, "");
        copyTree(generated, "gen/");
        gateway.withCopyFileToContainer(MountableFile.forHostPath(
                TestFiles.lab().resolve("share/complaints/2025-03-complaint-ref-4471.txt"), 0644),
            "/data/" + BUCKET + "/letters/2025-03-complaint-ref-4471.txt");
        gateway.start();
        source = source(Map.of());
        assets = S3.catalog(source).stream().collect(Collectors.toMap(AssetInfo::path, Function.identity()));
    }

    private static void copyTree(Path dir, String prefix) throws IOException {
        try (Stream<Path> files = Files.walk(dir)) {
            for (Path f : files.filter(Files::isRegularFile).toList()) {
                String key = prefix + dir.relativize(f).toString().replace('\\', '/');
                gateway.withCopyFileToContainer(MountableFile.forHostPath(f, 0644), "/data/" + BUCKET + "/" + key);
            }
        }
    }

    @AfterAll
    static void stop() {
        if (gateway != null) {
            gateway.stop();
        }
    }

    static SourceConfig source(Map<String, String> extra) {
        Map<String, String> s = new HashMap<>(Map.of("endpoint",
            "http://" + gateway.getHost() + ":" + gateway.getMappedPort(7070), "bucket", BUCKET,
            "access_key_id", KEY_ID, "path_style", "true"));
        s.putAll(extra);
        return new SourceConfig("bucket", "s3", s, SECRET);
    }

    @Test
    void catalogueListsEveryObjectAndRecordsTheDocument() {
        assertEquals(List.of("exports/customers_export_2025.csv", "finance/invoices_2024_2025.parquet",
            "gen/exports/customers.csv", "gen/finance/invoices.parquet", "gen/letters/complaint.txt",
            "gen/raw/invoices.json", "gen/raw/transactions.ndjson", "letters/2025-03-complaint-ref-4471.txt",
            "raw/transactions/transactions_2025-q1.ndjson"), assets.keySet().stream().sorted().toList());

        AssetInfo csv = assets.get("exports/customers_export_2025.csv");
        assertEquals("lab-raw/exports", csv.container());
        assertEquals("customers_export_2025.csv", csv.name());
        assertEquals("timestamp_tz", TestFiles.kinds(csv).get("created_at"));
        assertEquals("date", TestFiles.kinds(csv).get("date_of_birth"));

        AssetInfo pq = assets.get("finance/invoices_2024_2025.parquet");
        assertEquals(Map.of("invoice_id", "int64", "cust_ref", "int64", "issued_on", "date", "total", "float64",
            "paid", "text"), TestFiles.kinds(pq), "the Parquet footer is read with ranged requests");

        AssetInfo letter = assets.get("letters/2025-03-complaint-ref-4471.txt");
        assertEquals("document", letter.kind());
        assertFalse(letter.readable());
        assertEquals("498", letter.hints().get("size"));
    }

    @Test
    void readsEveryDataFileInFull() throws Exception {
        Map<String, String> expected = Map.of(
            "exports/customers_export_2025.csv", "2000",
            "raw/transactions/transactions_2025-q1.ndjson", "5000",
            "finance/invoices_2024_2025.parquet", "20000",
            "gen/exports/customers.csv", "3000",
            "gen/raw/transactions.ndjson", "1999",
            "gen/raw/invoices.json", "500",
            "gen/finance/invoices.parquet", "1000");
        for (var e : expected.entrySet()) {
            TestFiles.Read r = TestFiles.read(S3, source, assets.get(e.getKey()), 0);
            assertTrue(r.result().succeeded(), e.getKey() + ": " + r.result().error());
            assertEquals(e.getValue(), r.rowCount().get("rows"), e.getKey());
            assertEquals("true", r.rowCount().get("complete"), e.getKey());
        }
        TestFiles.Read csv = TestFiles.read(S3, source, assets.get("gen/exports/customers.csv"), 0);
        assertEquals("1", csv.rowCount().get("unparsed"));
        assertEquals("60", csv.profile().get("email").get("nulls"));
    }

    @Test
    void aRowLimitStopsTheTransferEarly() throws Exception {
        for (String key : List.of("raw/transactions/transactions_2025-q1.ndjson", "finance/invoices_2024_2025.parquet",
            "exports/customers_export_2025.csv")) {
            TestFiles.Read r = TestFiles.read(S3, source, assets.get(key), 100);
            assertTrue(r.result().succeeded(), r.result().error());
            assertEquals("100", r.rowCount().get("rows"), key);
            assertEquals("false", r.rowCount().get("complete"), key);
        }
    }

    @Test
    void aPrefixNarrowsTheCatalogue() throws Exception {
        List<String> keys = S3.catalog(source(Map.of("prefix", "raw/"))).stream().map(AssetInfo::path).toList();
        assertEquals(List.of("raw/transactions/transactions_2025-q1.ndjson"), keys);
    }

    @Test
    void wrongCredentialsAndMissingBucketsAreExplained() {
        ConnectorException wrongSecret = assertThrows(ConnectorException.class, () -> S3.catalog(
            new SourceConfig("bucket", "s3", source.settings(), "not-the-secret")));
        assertTrue(wrongSecret.getMessage().contains("secret"), wrongSecret.getMessage());

        ConnectorException noBucket = assertThrows(ConnectorException.class,
            () -> S3.catalog(source(Map.of("bucket", "no-such-bucket"))));
        assertTrue(noBucket.getMessage().contains("does not exist"), noBucket.getMessage());

        ConnectorException unreachable = assertThrows(ConnectorException.class,
            () -> S3.catalog(source(Map.of("endpoint", "http://127.0.0.1:1"))));
        assertTrue(unreachable.getMessage().contains("cannot reach"), unreachable.getMessage());
    }

    @Test
    void anObjectDeletedSinceTheCatalogueFailsClearly() throws Exception {
        AssetInfo gone = new AssetInfo("exports/gone.csv", "file", "lab-raw/exports", "gone.csv", null, List.of(),
            List.of(), false, true, Map.of("format", "csv"));
        TestFiles.Read r = TestFiles.read(S3, source, gone, 0);
        assertFalse(r.result().succeeded());
        assertTrue(r.result().error().contains("scan the source again"), r.result().error());
    }
}

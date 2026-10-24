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

    /** VersityGW v1.10.0, pinned by digest. */
    static final String IMAGE = "ghcr.io/versity/versitygw:v1.10.0"
        + "@sha256:89cad13cc981986ee284cbafea4613f9533ce6fd5f37a2c96c4c4fb8adb3a316";
    static final String BUCKET = "lab-raw";
    /** A CSV object large enough that reading it all would show in a byte count. */
    static final int BIG_ROWS = 550_000;
    static final String KEY_ID = "kilnmark-test";
    static final String SECRET = "kilnmark-test-secret";

    @TempDir
    static Path tmp;
    static Path generated;

    static GenericContainer<?> gateway;
    static final S3Connector S3 = new S3Connector();
    static SourceConfig source;
    static Map<String, AssetInfo> assets;

    @BeforeAll
    static void start() throws Exception {
        generated = Files.createDirectories(tmp.resolve("generated"));
        TestFiles.write(generated);
        Path big = Files.createDirectories(tmp.resolve("big")).resolve("big.csv");
        try (var w = Files.newBufferedWriter(big)) {
            w.write("id,name,amount,created_at\n");
            for (int i = 0; i < BIG_ROWS; i++) {
                w.write(i + ",customer number " + i + "," + (i % 1000) + ".25,2025-01-01 10:00:00+01\n");
            }
        }
        gateway = new GenericContainer<>(DockerImageName.parse(IMAGE))
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
        gateway.withCopyFileToContainer(MountableFile.forHostPath(big, 0644), "/data/" + BUCKET + "/big/big.csv");
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
        assertEquals(List.of("big/big.csv", "exports/customers_export_2025.csv", "finance/invoices_2024_2025.parquet",
            "gen/exports/customers.csv", "gen/finance/invoices.parquet", "gen/letters/complaint.txt",
            "gen/raw/invoices.json", "gen/raw/transactions.ndjson", "letters/2025-03-complaint-ref-4471.txt",
            "raw/transactions/transactions_2025-q1.ndjson"), assets.keySet().stream()
            .filter(k -> !k.startsWith("documents/")).sorted().toList());
        assertEquals(List.of("documents/2025-04-delivery-schedule.eml", "documents/depot-budget-2025.xlsx",
            "documents/depot-budget-2025.xlsx#Expenses", "documents/depot-budget-2025.xlsx#Headcount",
            "documents/depot-budget-2025.xlsx#Notes", "documents/onboarding-guide.docx",
            "documents/remote-working-policy.pdf", "documents/team-handbook.md"), assets.keySet().stream()
            .filter(k -> k.startsWith("documents/")).sorted().toList());

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
        assertTrue(letter.readable(), "its text is read");
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
    void aSampledReadStopsTheTransfer() throws Exception {
        long size = Long.parseLong(assets.get("big/big.csv").hints().get("size"));
        assertTrue(size > 30_000_000, "object of " + size + " bytes");
        try (CountingProxy proxy = new CountingProxy(gateway.getHost(), gateway.getMappedPort(7070))) {
            SourceConfig viaProxy = source(Map.of("endpoint", "http://127.0.0.1:" + proxy.port()));
            TestFiles.Read r = TestFiles.read(S3, viaProxy, assets.get("big/big.csv"), 100);
            assertTrue(r.result().succeeded(), r.result().error());
            assertEquals("100", r.rowCount().get("rows"));
            assertEquals("false", r.rowCount().get("complete"));
            Thread.sleep(500); // let the proxy see the connection close
            long transferred = proxy.bytesFromServer();
            assertTrue(transferred < size / 3, "transferred " + transferred + " of " + size + " bytes");

            proxy.reset();
            TestFiles.Read all = TestFiles.read(S3, viaProxy, assets.get("big/big.csv"), 0);
            assertEquals(Integer.toString(BIG_ROWS), all.rowCount().get("rows"));
            assertTrue(proxy.bytesFromServer() >= size, "a full read transfers the object: " + proxy.bytesFromServer());
        }
    }

    @Test
    void cancellingAStoreStopsRangeDownloadsInFlight() throws Exception {
        long size = Long.parseLong(assets.get("big/big.csv").hints().get("size"));
        try (CountingProxy slow = new CountingProxy(gateway.getHost(), gateway.getMappedPort(7070), 2)) {
            SourceConfig viaProxy = source(Map.of("endpoint", "http://127.0.0.1:" + slow.port()));
            S3FileStore store = (S3FileStore) S3.open(viaProxy);
            dev.hardwood.InputFile file = store.parquet("big/big.csv", size);
            java.util.concurrent.atomic.AtomicReference<Throwable> failure = new java.util.concurrent.atomic.AtomicReference<>();
            Thread reading = Thread.ofVirtual().start(() -> {
                try {
                    file.readRange(0, (int) size);
                } catch (Throwable t) {
                    failure.set(t);
                }
            });
            Thread.sleep(300);
            long start = System.nanoTime();
            store.cancel();
            reading.join(10_000);
            assertFalse(reading.isAlive(), "the read ends when the store is cancelled");
            assertTrue(System.nanoTime() - start < 5_000_000_000L);
            assertTrue(failure.get() != null && failure.get().getMessage().contains("stopped"), String.valueOf(failure.get()));
            Thread.sleep(300);
            assertTrue(slow.bytesFromServer() < size / 2, "transferred " + slow.bytesFromServer() + " of " + size);
            IOException refused = assertThrows(IOException.class, () -> file.readRange(0, 10));
            assertTrue(refused.getMessage().contains("stopped"), refused.getMessage());
            store.close();
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

    @Test
    void documentsAreCopiedReadAndTagged() throws Exception {
        AssetInfo pdf = assets.get("documents/remote-working-policy.pdf");
        assertTrue(pdf.readable());
        boolean tagged = tag("documents/remote-working-policy.pdf", Map.of("department", "people", "review", "2026"));
        DocumentReads.Read r = DocumentReads.read(S3, source, pdf);
        assertTrue(r.result().succeeded(), r.result().error());
        Map<String, String> d = r.document();
        assertEquals("true", d.get(io.kilnmark.kiln.plugins.documents.DocumentEvents.READABLE), d.toString());
        assertEquals("Remote working policy", d.get(io.kilnmark.kiln.plugins.documents.Extraction.TITLE));
        assertEquals("2", d.get(io.kilnmark.kiln.plugins.documents.Extraction.PAGES));
        assertFalse(r.chunks().isEmpty());
        if (pdf.hints().containsKey("owner")) {
            assertEquals(pdf.hints().get("owner"), d.get(io.kilnmark.kiln.plugins.documents.DocumentEvents.OWNER));
        }
        String tags = d.get(io.kilnmark.kiln.plugins.documents.DocumentEvents.TAGS);
        if (tagged) {
            assertEquals("{\"department\":\"people\",\"review\":\"2026\"}", tags);
        }
        // the copy made to read it is gone
        try (var left = Files.list(io.kilnmark.kiln.plugins.documents.DocumentRuntime.tempDir())) {
            assertEquals(0, left.count());
        }
    }

    @Test
    void workbookSheetsInABucketAreTables() throws Exception {
        AssetInfo expenses = assets.get("documents/depot-budget-2025.xlsx#Expenses");
        assertEquals("lab-raw/documents/depot-budget-2025.xlsx", expenses.container());
        TestFiles.Read r = TestFiles.read(S3, source, expenses, 0);
        assertTrue(r.result().succeeded(), r.result().error());
        assertEquals(Map.of("rows", "18", "complete", "true", "unparsed", "0"), r.rowCount());
        DocumentReads.Read mail = DocumentReads.read(S3, source, assets.get("documents/2025-04-delivery-schedule.eml"));
        assertEquals(List.of("may-schedule.md", "remote-working-policy.pdf"), mail.documents().stream().skip(1)
            .map(e -> e.attributes().get("name")).toList());
    }

    /** Tags an object; false when the gateway does not keep tags (it needs extended attributes). */
    private static boolean tag(String key, Map<String, String> tags) {
        try (var s3 = software.amazon.awssdk.services.s3.S3Client.builder()
            .endpointOverride(java.net.URI.create(source.setting("endpoint")))
            .region(software.amazon.awssdk.regions.Region.US_EAST_1).forcePathStyle(true)
            .httpClient(software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient.create())
            .credentialsProvider(software.amazon.awssdk.auth.credentials.StaticCredentialsProvider.create(
                software.amazon.awssdk.auth.credentials.AwsBasicCredentials.create(KEY_ID, SECRET))).build()) {
            s3.putObjectTagging(b -> b.bucket(BUCKET).key(key).tagging(t -> t.tagSet(tags.entrySet().stream()
                .map(e -> software.amazon.awssdk.services.s3.model.Tag.builder().key(e.getKey()).value(e.getValue())
                    .build()).toList())));
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }
}

package io.kilnmark.kiln.plugins.files;

import static io.kilnmark.kiln.plugins.write.Rows.field;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.arrow.vector.types.pojo.Schema;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

import io.kilnmark.kiln.MetadataEvent;
import io.kilnmark.kiln.RunResult;
import io.kilnmark.kiln.connect.Connector;
import io.kilnmark.kiln.connect.SourceConfig;
import io.kilnmark.kiln.plugins.write.Rows;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;

/**
 * Writing CSV and Parquet objects to S3-compatible storage (VersityGW, as {@link S3IngestTest}),
 * and reading them back with the S3 connector's own reader. Needs Docker; CI runs it.
 */
class S3WriteTest {

    static final String BUCKET = "lab-out";

    static GenericContainer<?> gateway;
    static final S3Connector S3 = new S3Connector();
    static SourceConfig source;
    static S3Client client;

    @BeforeAll
    static void start() {
        gateway = new GenericContainer<>(DockerImageName.parse(S3IngestTest.IMAGE))
            .withEnv("ROOT_ACCESS_KEY", S3IngestTest.KEY_ID)
            .withEnv("ROOT_SECRET_KEY", S3IngestTest.SECRET)
            .withCommand("posix", "/data")
            .withExposedPorts(7070)
            .waitingFor(Wait.forListeningPort());
        gateway.start();
        String endpoint = "http://" + gateway.getHost() + ":" + gateway.getMappedPort(7070);
        client = S3Client.builder().region(Region.US_EAST_1).forcePathStyle(true).endpointOverride(URI.create(endpoint))
            .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(S3IngestTest.KEY_ID,
                S3IngestTest.SECRET))).build();
        client.createBucket(b -> b.bucket(BUCKET));
        source = source(Map.of("prefix", "pipelines/"));
    }

    @AfterAll
    static void stop() {
        if (client != null) {
            client.close();
        }
        if (gateway != null) {
            gateway.stop();
        }
    }

    static SourceConfig source(Map<String, String> extra) {
        Map<String, String> s = new HashMap<>(Map.of("endpoint",
            "http://" + gateway.getHost() + ":" + gateway.getMappedPort(7070), "bucket", BUCKET,
            "access_key_id", S3IngestTest.KEY_ID, "path_style", "true"));
        s.putAll(extra);
        return new SourceConfig("bucket", "s3", s, S3IngestTest.SECRET);
    }

    static Connector.WriteOptions options(String path, Map<String, Object> settings) {
        return new Connector.WriteOptions(path, null, List.of(), null, settings, "urn:kilnmark:bucket:" + path);
    }

    static List<String> keys() {
        return client.listObjectsV2(b -> b.bucket(BUCKET)).contents().stream().map(o -> o.key()).sorted().toList();
    }

    @Test
    void parquetAndCsvRoundTripUnderThePrefix() throws Exception {
        List<Object[]> rows = FileWriteTest.allRows(3000);
        RunResult pq = FileWriteTest.write(S3, source, options("out/all.parquet", Map.of()), FileWriteTest.ALL, rows,
            500, -1, Rows.events());
        assertTrue(pq.succeeded(), pq.error());
        assertTrue(keys().contains("pipelines/out/all.parquet"), keys().toString());
        Rows.Collected back = FileWriteTest.read(S3, source, "pipelines/out/all.parquet");
        assertEquals(rows.size(), back.rows.size());
        for (int i = 0; i < rows.size(); i++) {
            for (int c = 0; c < rows.get(i).length; c++) {
                FileWriteTest.assertSame(rows.get(i)[c], back.rows.get(i).get(c), "row " + i + " column " + c);
            }
        }

        Schema schema = new Schema(List.of(field("id", Rows.INT64), field("note", Rows.TEXT)));
        List<MetadataEvent> events = Rows.events();
        RunResult csv = FileWriteTest.write(S3, source, options("out/notes.csv", Map.of()), schema,
            Rows.list(new Object[] {1L, "a, \"b\""}, new Object[] {2L, null}, new Object[] {3L, ""}), 2, -1, events);
        assertTrue(csv.succeeded(), csv.error());
        Rows.Collected notes = FileWriteTest.read(S3, source, "pipelines/out/notes.csv");
        assertEquals(List.of(List.of(1L, "a, \"b\""), java.util.Arrays.asList(2L, null), List.of(3L, "")), notes.rows);
        assertTrue(events.stream().anyMatch(e -> e.kind() == MetadataEvent.Kind.SCHEMA
            && "s3://lab-out/pipelines/out/notes.csv".equals(e.attributes().get("location"))));
    }

    @Test
    void aLargeFileGoesUpInPartsAndAppearsWhole() throws Exception {
        Schema schema = new Schema(List.of(field("id", Rows.INT64), field("text", Rows.TEXT)));
        List<Object[]> rows = new ArrayList<>();
        String pad = "y".repeat(200);
        for (int i = 0; i < 100_000; i++) { // about 20 MB: three parts
            rows.add(new Object[] {(long) i, pad + i});
        }
        RunResult r = FileWriteTest.write(S3, source, options("big/big.csv", Map.of()), schema, rows, 8192, -1,
            Rows.events());
        assertTrue(r.succeeded(), r.error());
        long size = client.headObject(b -> b.bucket(BUCKET).key("pipelines/big/big.csv")).contentLength();
        assertTrue(size > 2L * S3FileStore.PART_BYTES, "size " + size);
        assertEquals(rows.size(), FileWriteTest.read(S3, source, "pipelines/big/big.csv").rows.size());
    }

    @Test
    void aFailedWriteIsAbortedAndAnExistingObjectRefused() throws Exception {
        Schema schema = new Schema(List.of(field("id", Rows.INT64), field("text", Rows.TEXT)));
        List<Object[]> rows = new ArrayList<>();
        String pad = "z".repeat(500);
        for (int i = 0; i < 60_000; i++) {
            rows.add(new Object[] {(long) i, pad});
        }
        // fails after about 25 MB, so parts were already uploaded
        RunResult r = FileWriteTest.write(S3, source, options("failed/x.csv", Map.of()), schema, rows, 5000, 10,
            Rows.events());
        assertFalse(r.succeeded());
        assertFalse(keys().contains("pipelines/failed/x.csv"), "nothing appears");
        assertEquals(0, client.listMultipartUploads(b -> b.bucket(BUCKET)).uploads().size(), "the upload was aborted");

        RunResult first = FileWriteTest.write(S3, source, options("once.csv", Map.of()), schema,
            Rows.list(new Object[] {1L, "a"}), 10, -1, Rows.events());
        assertTrue(first.succeeded(), first.error());
        RunResult again = FileWriteTest.write(S3, source, options("once.csv", Map.of()), schema,
            Rows.list(new Object[] {2L, "b"}), 10, -1, Rows.events());
        assertFalse(again.succeeded());
        assertTrue(again.error().contains("already exists: set overwrite to true"), again.error());
        RunResult replaced = FileWriteTest.write(S3, source, options("once.csv", Map.of("overwrite", "true")), schema,
            Rows.list(new Object[] {3L, "c"}), 10, -1, Rows.events());
        assertTrue(replaced.succeeded(), replaced.error());
        assertEquals(List.of(List.of(3L, "c")), FileWriteTest.read(S3, source, "pipelines/once.csv").rows);
    }
}

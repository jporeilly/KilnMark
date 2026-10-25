package io.kilnmark.kiln.plugins.files;

import static io.kilnmark.kiln.plugins.write.Rows.field;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.apache.arrow.vector.types.pojo.Schema;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import io.kilnmark.kiln.MetadataEvent;
import io.kilnmark.kiln.RunResult;
import io.kilnmark.kiln.Step;
import io.kilnmark.kiln.StepSpec;
import io.kilnmark.kiln.connect.AssetInfo;
import io.kilnmark.kiln.connect.Connector;
import io.kilnmark.kiln.connect.ConnectorException;
import io.kilnmark.kiln.connect.SourceConfig;
import io.kilnmark.kiln.plugins.write.Rows;

/** Writing CSV and Parquet files into a folder, and reading them back with the files connector's own reader. */
class FileWriteTest {

    @TempDir
    static Path tmp;

    static Path root;
    static Path outside;
    static FilesConnector files;

    @BeforeAll
    static void setUp() throws Exception {
        root = Files.createDirectories(tmp.resolve("root"));
        outside = Files.createDirectories(tmp.resolve("outside"));
        files = TestFiles.files(root);
    }

    static SourceConfig folder(String name) throws Exception {
        Path d = Files.createDirectories(root.resolve(name));
        return new SourceConfig(name, "files", Map.of("path", d.toString()), null);
    }

    static Connector.WriteOptions options(String path, String format, Map<String, Object> settings) {
        return new Connector.WriteOptions(path, null, List.of(), format, settings, "urn:kilnmark:out:" + path);
    }

    static final Schema ALL = Rows.ALL_KINDS;

    static List<Object[]> allRows(int n) {
        return Rows.allKinds(n);
    }

    static RunResult write(Connector connector, SourceConfig source, Connector.WriteOptions options, Schema schema,
                           List<Object[]> rows, int batch, int failAt, List<MetadataEvent> events) throws Exception {
        Step writer = connector.writer(source, options);
        return Rows.run(Rows.source(schema, rows, batch, failAt), writer, events);
    }

    static Rows.Collected read(Connector connector, SourceConfig source, String path) throws Exception {
        AssetInfo asset = connector.asset(source, path);
        Rows.Collected c = new Rows.Collected();
        StepSpec reader = connector.reader(source, asset, new Connector.ReadOptions("urn:kilnmark:x:" + path, 0, 1000));
        RunResult r = Rows.run(reader, new StepSpec("collect", Rows.collector(c), Map.of()), Rows.events());
        assertTrue(r.succeeded(), r.error());
        return c;
    }

    @ParameterizedTest
    @ValueSource(strings = {"snappy", "zstd", "gzip", "none"})
    void parquetRoundTripsEveryKindThroughTheReader(String compression) throws Exception {
        SourceConfig s = folder("pq-" + compression);
        List<Object[]> rows = allRows(2500);
        List<MetadataEvent> events = Rows.events();
        RunResult r = write(files, s, options("out/all.parquet", null, Map.of("compression", compression)), ALL, rows,
            700, -1, events);
        assertTrue(r.succeeded(), r.error());

        Rows.Collected back = read(files, s, "out/all.parquet");
        assertEquals(List.of("int64", "int16", "int16", "int32", "float32", "float64", "decimal", "decimal", "decimal",
            "boolean", "text", "binary", "date", "time", "timestamp", "timestamp_tz"),
            back.columns.stream().map(c -> c.kindName()).toList());
        assertEquals(rows.size(), back.rows.size());
        for (int i = 0; i < rows.size(); i++) {
            Object[] want = rows.get(i);
            List<Object> got = back.rows.get(i);
            for (int c = 0; c < want.length; c++) {
                assertSame(want[c], got.get(c), "row " + i + " column " + ALL.getFields().get(c).getName());
            }
        }
        assertEquals(9, back.columns.get(6).precision());
        assertEquals(38, back.columns.get(8).precision());
        assertEquals(10, back.columns.get(8).scale());
    }

    static void assertSame(Object want, Object got, String where) {
        Rows.assertSameValue(want, got, where);
    }

    @Test
    void parquetWithSeveralRowGroupsAndPagesReadsBack() throws Exception {
        List<org.apache.arrow.vector.types.pojo.Field> fields = ALL.getFields();
        Schema schema = new Schema(List.of(fields.get(0), fields.get(9), fields.get(10)));
        List<Object[]> rows = new ArrayList<>();
        String pad = "x".repeat(300);
        for (int i = 0; i < 20_000; i++) {
            rows.add(new Object[] {(long) i, i % 5 == 0 ? null : i % 2 == 0, i % 13 == 0 ? null : pad + i});
        }
        SourceConfig s = folder("pq-groups");
        Path file = Path.of(s.setting("path")).resolve("groups.parquet");
        try (var out = Files.newOutputStream(file); var alloc = new org.apache.arrow.memory.RootAllocator()) {
            // a small row group size, so 6 MB of text makes several row groups of several 1 MiB pages
            ParquetEncoder enc = new ParquetEncoder(io.kilnmark.kiln.plugins.write.WriteColumn.of(schema), out,
                ParquetEncoder.Codec.NONE, 2L << 20);
            for (int from = 0; from < rows.size(); from += 4096) {
                try (var root = org.apache.arrow.vector.VectorSchemaRoot.create(schema, alloc)) {
                    root.allocateNew();
                    int n = Math.min(4096, rows.size() - from);
                    for (int r = 0; r < n; r++) {
                        for (int c = 0; c < 3; c++) {
                            RowsAccess.set(root.getVector(c), r, rows.get(from + r)[c]);
                        }
                    }
                    root.setRowCount(n);
                    enc.write(root);
                }
            }
            enc.finish();
        }
        Rows.Collected back = read(files, s, "groups.parquet");
        assertEquals(rows.size(), back.rows.size());
        for (int i = 0; i < rows.size(); i++) {
            assertEquals(java.util.Arrays.asList(rows.get(i)), back.rows.get(i), "row " + i);
        }
    }

    @Test
    void parquetIsReadableWithNoRows() throws Exception {
        SourceConfig s = folder("pq-empty");
        RunResult r = write(files, s, options("empty.parquet", "parquet", Map.of()), ALL, List.of(), 10, -1,
            Rows.events());
        assertTrue(r.succeeded(), r.error());
        Rows.Collected back = read(files, s, "empty.parquet");
        assertEquals(0, back.rows.size());
        assertEquals(16, back.columns.size());
    }

    @Test
    void csvRoundTripsQuotingNullsAndEmptyStringsWithTheReader() throws Exception {
        SourceConfig s = folder("csv");
        Schema schema = new Schema(List.of(field("id", Rows.INT64), field("text", Rows.TEXT),
            field("amount", Rows.decimal(10, 2)), field("day", Rows.DATE), field("at", Rows.TIMESTAMP_TZ)));
        List<Object[]> rows = Rows.list(
            new Object[] {1L, "plain", new BigDecimal("1.50"), LocalDate.of(2025, 1, 31),
                Instant.parse("2025-01-31T09:30:00Z")},
            new Object[] {2L, "comma, inside", new BigDecimal("-2.25"), null, Instant.parse("2025-02-01T00:00:00.5Z")},
            new Object[] {3L, "quote \" inside", null, LocalDate.of(1999, 12, 31), null},
            new Object[] {4L, "line\nbreak and\r\ncrlf", new BigDecimal("0.01"), LocalDate.of(2000, 2, 29),
                Instant.parse("2024-02-29T23:59:59.123456Z")},
            new Object[] {5L, "", new BigDecimal("100.00"), LocalDate.of(2025, 6, 1), Instant.parse("2025-06-01T12:00:00Z")},
            new Object[] {6L, null, new BigDecimal("3.00"), LocalDate.of(2025, 6, 2), Instant.parse("2025-06-02T12:00:00Z")},
            new Object[] {7L, "  spaces kept  ", new BigDecimal("4.10"), LocalDate.of(2025, 6, 3),
                Instant.parse("2025-06-03T12:00:00Z")},
            new Object[] {8L, "ünïcödé ✓ 日本", new BigDecimal("5.55"), LocalDate.of(2025, 6, 4),
                Instant.parse("2025-06-04T12:00:00Z")});
        RunResult r = write(files, s, options("exports/data.csv", "csv", Map.of()), schema, rows, 3, -1, Rows.events());
        assertTrue(r.succeeded(), r.error());

        String text = Files.readString(Path.of(s.setting("path")).resolve("exports/data.csv"), StandardCharsets.UTF_8);
        assertTrue(text.startsWith("id,text,amount,day,at\r\n1,plain,1.50,2025-01-31,2025-01-31 09:30:00Z\r\n"), text);
        assertTrue(text.contains("\r\n2,\"comma, inside\",-2.25,,2025-02-01 00:00:00.5Z\r\n"), text);
        assertTrue(text.contains("\r\n3,\"quote \"\" inside\",,1999-12-31,\r\n"), text);
        assertTrue(text.contains("\r\n5,\"\",100.00,"), "an empty string is quoted: " + text);
        assertTrue(text.contains("\r\n6,,3.00,"), "a null is an empty field: " + text);

        Rows.Collected back = read(files, s, "exports/data.csv");
        assertEquals(List.of("int64", "text", "decimal", "date", "timestamp_tz"),
            back.columns.stream().map(c -> c.kindName()).toList());
        assertEquals(rows.size(), back.rows.size());
        for (int i = 0; i < rows.size(); i++) {
            for (int c = 0; c < 5; c++) {
                assertSame(rows.get(i)[c], back.rows.get(i).get(c), "row " + i + " column " + c);
            }
        }
    }

    @Test
    void csvTakesAnotherDelimiter() throws Exception {
        SourceConfig s = folder("csv-semicolon");
        Schema schema = new Schema(List.of(field("a", Rows.TEXT), field("b", Rows.INT32)));
        RunResult r = write(files, s, options("x.csv", null, Map.of("delimiter", ";")), schema,
            Rows.list(new Object[] {"one;two", 1}, new Object[] {"three,four", 2}), 10, -1, Rows.events());
        assertTrue(r.succeeded(), r.error());
        assertEquals("a;b\r\n\"one;two\";1\r\nthree,four;2\r\n",
            Files.readString(Path.of(s.setting("path")).resolve("x.csv")));
        assertThrows(ConnectorException.class, () -> files.writer(s, options("y.csv", null, Map.of("delimiter", "\""))));
        assertThrows(ConnectorException.class, () -> files.writer(s, options("y.csv", null, Map.of("delimiter", ";;"))));
    }

    @Test
    void anExistingFileIsRefusedUnlessOverwriteIsAsked() throws Exception {
        SourceConfig s = folder("overwrite");
        Path file = Path.of(s.setting("path")).resolve("keep.csv");
        Files.writeString(file, "old\r\n1\r\n");
        Schema schema = new Schema(List.of(field("new", Rows.INT64)));
        List<Object[]> rows = Rows.list(new Object[] {7L});

        RunResult refused = write(files, s, options("keep.csv", null, Map.of()), schema, rows, 10, -1, Rows.events());
        assertFalse(refused.succeeded());
        assertTrue(refused.error().contains("already exists: set overwrite to true"), refused.error());
        assertEquals("old\r\n1\r\n", Files.readString(file));

        RunResult replaced = write(files, s, options("keep.csv", null, Map.of("overwrite", true)), schema, rows, 10, -1,
            Rows.events());
        assertTrue(replaced.succeeded(), replaced.error());
        assertEquals("new\r\n7\r\n", Files.readString(file));

        Step viaMode = files.writer(s, new Connector.WriteOptions("keep.csv", "replace", List.of(), null, Map.of(),
            "urn:kilnmark:out:keep.csv"));
        assertTrue(Rows.run(Rows.source(schema, Rows.list(new Object[] {8L}), 10), viaMode, Rows.events()).succeeded());
        assertEquals("new\r\n8\r\n", Files.readString(file));

        assertTrue(assertThrows(ConnectorException.class, () -> files.writer(s, new Connector.WriteOptions("keep.csv",
            "append", List.of(), null, Map.of(), "urn:kilnmark:out:x"))).getMessage().startsWith("mode: "));
    }

    @Test
    void aFailedRunLeavesNoFileAndNoTemporaryFile() throws Exception {
        SourceConfig s = folder("failed");
        Path dir = Path.of(s.setting("path"));
        Files.writeString(dir.resolve("existing.parquet"), "not touched");
        Schema schema = new Schema(List.of(field("id", Rows.INT64)));
        List<Object[]> rows = new ArrayList<>();
        for (int i = 0; i < 1000; i++) {
            rows.add(new Object[] {(long) i});
        }
        RunResult r = write(files, s, options("new/fresh.csv", null, Map.of()), schema, rows, 100, 4, Rows.events());
        assertFalse(r.succeeded());
        assertTrue(r.error().contains("failed on purpose"), r.error());
        RunResult r2 = write(files, s, options("existing.parquet", null, Map.of("overwrite", true)), schema, rows, 100,
            4, Rows.events());
        assertFalse(r2.succeeded());
        assertEquals("not touched", Files.readString(dir.resolve("existing.parquet")));
        try (Stream<Path> all = Files.walk(dir)) {
            assertEquals(List.of("existing.parquet"), all.filter(Files::isRegularFile)
                .map(p -> dir.relativize(p).toString()).toList(), "no partial or temporary file is left");
        }
    }

    @Test
    void pathsLeadingOutsideTheFolderAreRefused() throws Exception {
        SourceConfig s = folder("escape");
        Path dir = Path.of(s.setting("path"));
        Files.createSymbolicLink(dir.resolve("link"), outside);
        Schema schema = new Schema(List.of(field("id", Rows.INT64)));

        RunResult r = write(files, s, options("link/x.csv", null, Map.of()), schema, Rows.list(new Object[] {1L}), 10, -1,
            Rows.events());
        assertFalse(r.succeeded());
        assertTrue(r.error().contains("outside the folder"), r.error());
        try (Stream<Path> out = Files.list(outside)) {
            assertEquals(0, out.count(), "nothing is written through the link");
        }
        for (String bad : List.of("../x.csv", "/tmp/x.csv", "a\\..\\x.csv", "a//x.csv")) {
            ConnectorException e = assertThrows(ConnectorException.class, () -> files.writer(s,
                options(bad, null, Map.of())));
            assertTrue(e.getMessage().startsWith("path: "), e.getMessage());
        }
        // the store checks again, whatever reaches it
        LocalFileStore store = new LocalFileStore(dir, null, true);
        for (String bad : List.of("../x.csv", "/x.csv", "a/../../x.csv")) {
            assertThrows(java.io.IOException.class, () -> store.create(bad, false), bad);
        }
    }

    @Test
    void whatIsWrittenIsProfiledAndReportedForTheAsset() throws Exception {
        SourceConfig s = folder("events");
        Schema schema = new Schema(List.of(field("id", Rows.INT64), field("email", Rows.TEXT)));
        List<Object[]> rows = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            rows.add(new Object[] {(long) i, i % 10 == 0 ? null : "person" + i + "@example.com"});
        }
        List<MetadataEvent> events = Rows.events();
        RunResult r = write(files, s, options("people.parquet", null, Map.of()), schema, rows, 16, -1, events);
        assertTrue(r.succeeded(), r.error());
        String urn = "urn:kilnmark:out:people.parquet";
        assertTrue(events.stream().allMatch(e -> e.assetUrn().equals(urn)));
        MetadataEvent schemaEvent = events.stream().filter(e -> e.kind() == MetadataEvent.Kind.SCHEMA).findFirst()
            .orElseThrow();
        assertEquals("[{\"name\":\"id\",\"kind\":\"int64\",\"type\":\"INT64\"},{\"name\":\"email\",\"kind\":\"text\","
            + "\"type\":\"BYTE_ARRAY STRING\"}]", schemaEvent.attributes().get("columns"));
        assertEquals("parquet", schemaEvent.attributes().get("format"));
        Map<String, String> count = events.stream().filter(e -> e.kind() == MetadataEvent.Kind.ROW_COUNT).findFirst()
            .orElseThrow().attributes();
        assertEquals("50", count.get("rows"));
        assertEquals("true", count.get("complete"));
        Map<String, Map<String, String>> profile = new HashMap<>();
        events.stream().filter(e -> e.kind() == MetadataEvent.Kind.PROFILE)
            .forEach(e -> profile.put(e.attributes().get("field"), e.attributes()));
        assertEquals(java.util.Set.of("id", "email"), profile.keySet());
        assertEquals("5", profile.get("email").get("nulls"));
        assertTrue(events.stream().anyMatch(e -> e.kind() == MetadataEvent.Kind.SAMPLE));

        List<MetadataEvent> quiet = Rows.events();
        assertTrue(write(files, s, options("quiet.csv", null, Map.of("profile", false)), schema, rows, 16, -1, quiet)
            .succeeded());
        assertTrue(quiet.stream().noneMatch(e -> e.kind() == MetadataEvent.Kind.PROFILE));
        List<MetadataEvent> failed = Rows.events();
        assertFalse(write(files, s, options("failed.csv", null, Map.of()), schema, rows, 16, 1, failed).succeeded());
        assertTrue(failed.isEmpty(), "nothing is reported for a write that failed");
    }

    @Test
    void theFileConnectorsListWriting() {
        assertTrue(files.capabilities().contains(Connector.Capability.WRITE));
        assertTrue(new S3Connector().capabilities().contains(Connector.Capability.WRITE));
    }

    @Test
    void parquetDecimalWidthsFollowTheSpecification() {
        assertEquals(4, ParquetEncoder.decimalBytes(9));
        assertEquals(8, ParquetEncoder.decimalBytes(18));
        assertEquals(9, ParquetEncoder.decimalBytes(19));
        assertEquals(16, ParquetEncoder.decimalBytes(38));
    }

    /** Package access to the test source's setter, for writing batches by hand. */
    static final class RowsAccess {
        static void set(org.apache.arrow.vector.FieldVector v, int row, Object value) {
            if (value == null) {
                v.setNull(row);
            } else if (v instanceof org.apache.arrow.vector.BigIntVector x) {
                x.setSafe(row, (Long) value);
            } else if (v instanceof org.apache.arrow.vector.BitVector x) {
                x.setSafe(row, (Boolean) value ? 1 : 0);
            } else {
                ((org.apache.arrow.vector.VarCharVector) v).setSafe(row, ((String) value).getBytes(StandardCharsets.UTF_8));
            }
        }
    }
}

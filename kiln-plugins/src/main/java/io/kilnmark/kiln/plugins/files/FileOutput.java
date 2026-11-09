package io.kilnmark.kiln.plugins.files;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import org.apache.arrow.vector.VectorSchemaRoot;

import io.kilnmark.kiln.Node;
import io.kilnmark.kiln.NodeContext;
import io.kilnmark.kiln.NodeException;
import io.kilnmark.kiln.connect.AssetInfo;
import io.kilnmark.kiln.connect.ConnectorException;
import io.kilnmark.kiln.plugins.write.WriteColumn;
import io.kilnmark.kiln.plugins.write.WriteReport;
import io.kilnmark.kiln.plugins.write.WriteSettings;

/**
 * Writes the batches arriving on its {@code in} port to one CSV or Parquet file in a folder or
 * bucket. The file appears only when every row is written: a local file is written under a hidden
 * temporary name and renamed, an object is uploaded and completed at the end. If the run fails or
 * is stopped, what was written is discarded and an existing file of that name is left as it was.
 * What was written is profiled in the same pass and reported once the file is in place
 * ({@link WriteReport}).
 */
final class FileOutput implements Node {

    static final String TYPE = "file-output";

    private final FileStore.Opener opener;
    private final String storePath;
    private final FileTarget target;
    private final boolean overwrite;
    private final char delimiter;
    private final ParquetEncoder.Codec codec;
    private final WriteSettings settings;
    private final String assetUrn;
    private final FileInspector.TextOptions reading;
    private final Map<String, String> profiling;

    private FileStore store;
    private FileStore.Upload upload;
    private RowEncoder encoder;
    private List<WriteColumn> columns;
    private WriteReport report;
    private boolean committed;

    /**
     * @param storePath where the store puts the file (with an S3 source's prefix)
     * @param target    the checked target and format, as the pipeline gave them
     * @param overwrite whether an existing file may be replaced
     * @param delimiter the CSV delimiter setting (the node's, else the source's), or null for a comma
     * @param reading   how the source reads text files, to record a CSV file's columns as a scan will
     */
    FileOutput(FileStore.Opener opener, String storePath, FileTarget target, boolean overwrite, WriteSettings settings,
               String assetUrn, Map<String, String> profiling, String delimiter, FileInspector.TextOptions reading)
        throws ConnectorException {
        this.profiling = profiling;
        this.reading = reading;
        this.opener = opener;
        this.storePath = storePath;
        this.target = target;
        this.settings = settings;
        this.assetUrn = assetUrn;
        this.overwrite = overwrite;
        this.delimiter = CsvEncoder.delimiter(delimiter);
        this.codec = ParquetEncoder.Codec.of(settings.text("compression", null));
    }

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public void init(NodeContext context) throws NodeException {
        try {
            report = new WriteReport(context.nodeName(), assetUrn, settings, profiling, true);
        } catch (ConnectorException e) {
            throw new NodeException("Node " + context.nodeName() + ": " + e.getMessage(), e);
        }
        try {
            store = opener.open();
            upload = store.create(storePath, overwrite);
        } catch (IOException | RuntimeException e) {
            throw new NodeException("Cannot write " + target.path() + ": " + message(e), e);
        }
    }

    @Override
    public boolean process(NodeContext context) throws NodeException {
        VectorSchemaRoot batch;
        try {
            batch = context.input().take(context.allocator());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new NodeException("Interrupted", e);
        }
        if (batch == null) {
            finish(context);
            return false;
        }
        try {
            if (encoder == null) {
                columns = WriteColumn.of(batch.getSchema());
                encoder = target.format().equals("parquet")
                    ? new ParquetEncoder(columns, upload.stream(), codec, ParquetEncoder.ROW_GROUP_BYTES_DEFAULT)
                    : new CsvEncoder(columns, upload.stream(), delimiter);
            }
            report.accept(batch);
            encoder.write(batch);
        } catch (IOException | RuntimeException e) {
            throw new NodeException("Writing " + target.path() + " failed after " + report.rows() + " rows: "
                + message(e), e);
        } finally {
            batch.close();
        }
        return true;
    }

    private void finish(NodeContext context) throws NodeException {
        if (encoder == null) {
            throw new NodeException("Nothing arrived to write to " + target.path()
                + ": the node before sent no columns");
        }
        try {
            encoder.finish();
            upload.commit();
            committed = true;
        } catch (IOException | RuntimeException e) {
            throw new NodeException("Cannot finish " + target.path() + ": " + message(e), e);
        }
        List<String> kinds = List.of();
        List<String> types = encoder.types();
        if (target.format().equals("csv")) {
            // a scan infers a CSV file's kinds from its text: record those, so the next scan sees no change
            try {
                AssetInfo seen = FileInspector.inspect(store, store.entry(storePath), "", reading);
                if (seen.fields().size() == columns.size()) {
                    kinds = seen.fields().stream().map(AssetInfo.FieldInfo::kind).toList();
                    types = seen.fields().stream().map(AssetInfo.FieldInfo::sourceType).toList();
                }
            } catch (IOException | RuntimeException e) {
                // the input's kinds, then; the next scan corrects them
            }
        }
        Map<String, String> details = new java.util.LinkedHashMap<>(Map.of("target", target.path(),
            "format", target.format(), "location", store.describe(storePath)));
        details.putAll(onDisk(store, storePath));
        report.emit(context.metadata(), columns, kinds, types, details);
    }

    /**
     * Where a file written to a folder on this server is, and its size and time, read just after it
     * was put in place: what a person downloading it later is checked against, so they get this
     * run's file or nothing. Empty for a bucket, and when they cannot be read (the file is still
     * written; the download then says it may have changed).
     */
    static Map<String, String> onDisk(FileStore store, String storePath) {
        try {
            java.nio.file.Path local = store.localPath(storePath);
            if (local == null) {
                return Map.of();
            }
            java.nio.file.attribute.BasicFileAttributes a = java.nio.file.Files.readAttributes(local,
                java.nio.file.attribute.BasicFileAttributes.class, java.nio.file.LinkOption.NOFOLLOW_LINKS);
            if (!a.isRegularFile()) {
                return Map.of();
            }
            return Map.of(FilesConnector.WRITTEN_FILE, local.toString(), FilesConnector.WRITTEN_BYTES,
                Long.toString(a.size()), FilesConnector.WRITTEN_MODIFIED, a.lastModifiedTime().toInstant().toString());
        } catch (IOException | RuntimeException e) {
            return Map.of();
        }
    }

    @Override
    public void close() {
        if (upload != null && !committed) {
            upload.abort();
        }
        if (store != null) {
            try {
                store.close();
            } catch (IOException | RuntimeException e) {
                // nothing left to release that matters
            }
        }
    }

    private static String message(Throwable e) {
        return e.getMessage() == null || e.getMessage().isBlank() ? e.getClass().getSimpleName() : e.getMessage();
    }
}

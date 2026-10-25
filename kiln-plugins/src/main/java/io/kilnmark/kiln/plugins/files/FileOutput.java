package io.kilnmark.kiln.plugins.files;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import org.apache.arrow.vector.VectorSchemaRoot;

import io.kilnmark.kiln.Step;
import io.kilnmark.kiln.StepContext;
import io.kilnmark.kiln.StepException;
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
final class FileOutput implements Step {

    static final String TYPE = "file-output";

    private final FileStore.Opener opener;
    private final String storePath;
    private final FileTarget target;
    private final boolean overwrite;
    private final char delimiter;
    private final ParquetEncoder.Codec codec;
    private final WriteSettings settings;
    private final String assetUrn;

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
     */
    FileOutput(FileStore.Opener opener, String storePath, FileTarget target, boolean overwrite, WriteSettings settings,
               String assetUrn) throws ConnectorException {
        this.opener = opener;
        this.storePath = storePath;
        this.target = target;
        this.settings = settings;
        this.assetUrn = assetUrn;
        this.overwrite = overwrite;
        this.delimiter = CsvEncoder.delimiter(settings.text("delimiter", null));
        this.codec = ParquetEncoder.Codec.of(settings.text("compression", null));
    }

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public void init(StepContext context) throws StepException {
        try {
            report = new WriteReport(context.stepName(), assetUrn, settings, true);
        } catch (ConnectorException e) {
            throw new StepException("Step " + context.stepName() + ": " + e.getMessage(), e);
        }
        try {
            store = opener.open();
            upload = store.create(storePath, overwrite);
        } catch (IOException | RuntimeException e) {
            throw new StepException("Cannot write " + target.path() + ": " + message(e), e);
        }
    }

    @Override
    public boolean process(StepContext context) throws StepException {
        VectorSchemaRoot batch;
        try {
            batch = context.input().take(context.allocator());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new StepException("Interrupted", e);
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
            throw new StepException("Writing " + target.path() + " failed after " + report.rows() + " rows: "
                + message(e), e);
        } finally {
            batch.close();
        }
        return true;
    }

    private void finish(StepContext context) throws StepException {
        if (encoder == null) {
            throw new StepException("Nothing arrived to write to " + target.path()
                + ": the step before sent no columns");
        }
        try {
            encoder.finish();
            upload.commit();
            committed = true;
        } catch (IOException | RuntimeException e) {
            throw new StepException("Cannot finish " + target.path() + ": " + message(e), e);
        }
        report.emit(context.metadata(), columns, encoder.types(), Map.of("target", target.path(),
            "format", target.format(), "location", store.describe(storePath)));
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

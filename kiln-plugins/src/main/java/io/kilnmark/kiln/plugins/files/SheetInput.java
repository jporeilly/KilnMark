package io.kilnmark.kiln.plugins.files;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.arrow.vector.FieldVector;
import org.apache.arrow.vector.VectorSchemaRoot;
import org.apache.arrow.vector.types.pojo.Field;
import org.apache.arrow.vector.types.pojo.Schema;

import io.kilnmark.kiln.MetadataEvent;
import io.kilnmark.kiln.Step;
import io.kilnmark.kiln.StepContext;
import io.kilnmark.kiln.StepException;
import io.kilnmark.kiln.plugins.documents.DocumentException;
import io.kilnmark.kiln.plugins.documents.DocumentLimits;
import io.kilnmark.kiln.plugins.documents.DocumentRuntime;
import io.kilnmark.kiln.plugins.documents.DocumentTypes;

/**
 * Reads one sheet of an Excel workbook as rows, streaming (.xlsx) so the profiler runs on it as
 * on any table. The workbook is copied to local disk first when it is remote. If the sheet's header
 * has changed since the catalogue, the columns are worked out again. Cells that do not fit their
 * column's kind are read as null and counted ({@code unparsed} in {@code ROW_COUNT}).
 *
 * <p>Settings: {@code path} (the workbook), {@code sheet}, {@code size}, {@code asset-urn},
 * {@code row-limit} (0 for all), {@code batch-size}.
 */
public final class SheetInput implements Step {

    public static final String TYPE = "sheet-input";

    private final FileStore.Opener opener;
    private final List<FileColumn> catalogued;
    private FileStore store;
    private LocalCopy copy;
    private Sheets.Workbook workbook;
    private Sheets.SheetRows rows;
    private List<FileColumn> columns;
    private Schema schema;
    private int first;
    private String assetUrn;
    private String label;
    private long rowLimit;
    private int batchSize;
    private long read;
    private long unparsed;
    private Sheets.SheetRow pending;

    public SheetInput(FileStore.Opener opener, List<FileColumn> columns) {
        this.opener = opener;
        this.catalogued = List.copyOf(columns);
    }

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public void init(StepContext context) throws StepException {
        String path = context.setting("path");
        String sheet = context.setting("sheet");
        assetUrn = context.setting("asset-urn");
        rowLimit = Long.parseLong(context.setting("row-limit", "0"));
        batchSize = Integer.parseInt(context.setting("batch-size", "8192"));
        long size = Long.parseLong(context.setting("size", "-1"));
        label = "sheet '" + sheet + "' of " + path;
        try {
            store = opener.open();
            label = "sheet '" + sheet + "' of " + store.describe(path);
            DocumentLimits limits = DocumentRuntime.limits();
            long max = DocumentTypes.legacy(path) ? Math.min(limits.maxBytes(), limits.maxLegacyBytes())
                : limits.maxBytes();
            if (size > max) {
                throw LocalCopy.tooLarge(size, max);
            }
            copy = LocalCopy.of(store, path, max);
            workbook = Sheets.open(copy.path(), path);
            Sheets.Layout layout;
            try (Sheets.SheetRows sample = workbook.rows(sheet)) {
                layout = Sheets.inspect(sheet, 0, sample, FileInspector.SAMPLE_ROWS);
            }
            if (layout.problem() != null) {
                throw new IOException("it is no longer a table: " + layout.problem());
            }
            List<String> names = layout.columns().stream().map(FileColumn::name).toList();
            columns = !catalogued.isEmpty() && names.equals(catalogued.stream().map(FileColumn::name).toList())
                ? catalogued : layout.columns();
            first = layout.firstColumn();
            rows = workbook.rows(sheet);
            Sheets.SheetRow r;
            while ((r = rows.next()) != null && r.number() < layout.headerRow()) {
                // before the header
            }
        } catch (IOException | DocumentException | RuntimeException e) {
            close();
            throw new StepException("Cannot read " + label + ": " + e.getMessage(), e);
        }
        List<Field> fields = new ArrayList<>();
        for (FileColumn c : columns) {
            fields.add(c.field());
        }
        schema = new Schema(fields);
    }

    @Override
    public boolean process(StepContext context) throws StepException {
        VectorSchemaRoot batch = VectorSchemaRoot.create(schema, context.allocator());
        boolean limitReached = false;
        boolean done = false;
        try {
            for (FieldVector v : batch.getFieldVectors()) {
                v.setInitialCapacity(batchSize);
            }
            batch.allocateNew();
            List<FieldVector> vectors = batch.getFieldVectors();
            int n = 0;
            while (n < batchSize) {
                if (rowLimit > 0 && read + n >= rowLimit) {
                    limitReached = true;
                    break;
                }
                Sheets.SheetRow r = nextRow();
                if (r == null) {
                    done = true;
                    break;
                }
                for (int c = 0; c < columns.size(); c++) {
                    if (!TextValues.write(columns.get(c), vectors.get(c), n, Sheets.text(r.cell(first + c)))) {
                        unparsed++;
                    }
                }
                n++;
            }
            batch.setRowCount(n);
            read += n;
        } catch (IOException | RuntimeException e) {
            batch.close();
            throw new StepException("Reading " + label + " failed after " + read + " rows: " + e.getMessage(), e);
        }
        try {
            if (batch.getRowCount() > 0 || read == 0) {
                context.output().put(batch);
            } else {
                batch.close();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new StepException("Interrupted", e);
        }
        if (limitReached) {
            boolean more;
            try {
                pending = nextRow();
                more = pending != null;
            } catch (IOException | RuntimeException e) {
                more = true;
            }
            finish(context, !more);
            return false;
        }
        if (done) {
            finish(context, true);
            return false;
        }
        return true;
    }

    private Sheets.SheetRow nextRow() throws IOException {
        if (pending != null) {
            Sheets.SheetRow r = pending;
            pending = null;
            return r;
        }
        Sheets.SheetRow r;
        while ((r = rows.next()) != null && r.blank()) {
            // blank rows are not rows of the table
        }
        return r;
    }

    private void finish(StepContext context, boolean complete) {
        Map<String, String> a = new HashMap<>();
        a.put("rows", Long.toString(read));
        a.put("complete", Boolean.toString(complete));
        a.put("unparsed", Long.toString(unparsed));
        context.metadata().emit(new MetadataEvent(assetUrn, MetadataEvent.Kind.ROW_COUNT, a, Instant.now()));
    }

    @Override
    public void close() {
        for (AutoCloseable c : new AutoCloseable[] {rows, workbook, copy}) {
            if (c != null) {
                try {
                    c.close();
                } catch (Exception e) {
                    // closing a read
                }
            }
        }
        rows = null;
        workbook = null;
        copy = null;
        if (store != null) {
            store.cancel();
            try {
                store.close();
            } catch (IOException | RuntimeException e) {
                // as above
            }
            store = null;
        }
    }
}

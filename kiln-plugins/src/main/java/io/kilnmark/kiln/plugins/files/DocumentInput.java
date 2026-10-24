package io.kilnmark.kiln.plugins.files;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import org.apache.arrow.vector.IntVector;
import org.apache.arrow.vector.VarCharVector;
import org.apache.arrow.vector.VectorSchemaRoot;

import io.kilnmark.kiln.MetadataEvent;
import io.kilnmark.kiln.Step;
import io.kilnmark.kiln.StepContext;
import io.kilnmark.kiln.StepException;
import io.kilnmark.kiln.plugins.documents.Chunker;
import io.kilnmark.kiln.plugins.documents.DocumentEvents;
import io.kilnmark.kiln.plugins.documents.DocumentException;
import io.kilnmark.kiln.plugins.documents.DocumentLimits;
import io.kilnmark.kiln.plugins.documents.DocumentRuntime;
import io.kilnmark.kiln.plugins.documents.DocumentTypes;
import io.kilnmark.kiln.plugins.documents.Extraction;
import io.kilnmark.kiln.plugins.documents.TextExtractor;

/**
 * Reads one document's text: copies it to local disk if it is remote, reads it with Tika within
 * the document limits ({@link DocumentRuntime}), and reports it. First it emits one
 * {@code DOCUMENT} event per document read (the file, and each attachment of a mail message, whose
 * event names the file as its {@code parent}); then it puts the text's chunks on its output as
 * rows ({@link DocumentEvents#CHUNK_SCHEMA}), and finally {@code ROW_COUNT} with the number of
 * chunks.
 *
 * <p>A document that cannot be read never fails the step: its event says {@code readable=false}
 * with the reason, and no chunks follow. With {@code keep-text=false} the profile is still worked
 * out from the text, but no chunk leaves the step.
 *
 * <p>Settings: {@code path}, {@code size}, {@code asset-urn}, {@code keep-text} (default true),
 * {@code owner}, {@code batch-size} (chunks per batch, default 256).
 */
public final class DocumentInput implements Step {

    public static final String TYPE = "document-input";

    private final FileStore.Opener opener;
    private FileStore store;
    private String path;
    private String assetUrn;
    private long size;
    private boolean keepText;
    private String owner;
    private int batchSize;
    private List<Row> rows;
    private int next;

    /** A chunk of one document: the file or one of its attachments. */
    private record Row(String urn, Chunker.Chunk chunk) {
    }

    public DocumentInput(FileStore.Opener opener) {
        this.opener = opener;
    }

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public void init(StepContext context) throws StepException {
        path = context.setting("path");
        assetUrn = context.setting("asset-urn");
        size = Long.parseLong(context.setting("size", "-1"));
        keepText = !"false".equalsIgnoreCase(context.setting("keep-text", "true"));
        owner = context.setting("owner", null);
        batchSize = Integer.parseInt(context.setting("batch-size", "256"));
        try {
            store = opener.open();
        } catch (IOException | RuntimeException e) {
            throw new StepException("Cannot open the source to read " + path + ": " + e.getMessage(), e);
        }
    }

    /** The URN of a mail message's {@code n}th attachment (1-based). */
    public static String attachmentUrn(String documentUrn, int n, String name) {
        String safe = name == null ? "attachment" : name.replaceAll("[\\p{Cntrl}/#\\\\]", "_");
        return documentUrn + "#attachments/" + n + "/" + safe;
    }

    @Override
    public boolean process(StepContext context) throws StepException {
        if (rows == null) {
            rows = new ArrayList<>();
            read(context);
        }
        if (next >= rows.size() && next > 0) {
            finish(context);
            return false;
        }
        int n = Math.min(batchSize, rows.size() - next);
        VectorSchemaRoot batch = VectorSchemaRoot.create(DocumentEvents.CHUNK_SCHEMA, context.allocator());
        try {
            batch.allocateNew();
            VarCharVector urn = (VarCharVector) batch.getVector(DocumentEvents.ASSET_URN);
            IntVector seq = (IntVector) batch.getVector(DocumentEvents.SEQUENCE);
            VarCharVector text = (VarCharVector) batch.getVector(DocumentEvents.TEXT);
            IntVector page = (IntVector) batch.getVector(DocumentEvents.PAGE);
            IntVector start = (IntVector) batch.getVector(DocumentEvents.START);
            IntVector end = (IntVector) batch.getVector(DocumentEvents.END);
            for (int i = 0; i < n; i++) {
                Row r = rows.get(next + i);
                urn.setSafe(i, r.urn().getBytes(StandardCharsets.UTF_8));
                seq.setSafe(i, r.chunk().sequence());
                text.setSafe(i, r.chunk().text().getBytes(StandardCharsets.UTF_8));
                if (r.chunk().page() == null) {
                    page.setNull(i);
                } else {
                    page.setSafe(i, r.chunk().page());
                }
                start.setSafe(i, r.chunk().start());
                end.setSafe(i, r.chunk().end());
            }
            batch.setRowCount(n);
            next += n;
            context.output().put(batch); // with no chunks, one empty batch still carries the schema
        } catch (InterruptedException e) {
            batch.close();
            Thread.currentThread().interrupt();
            throw new StepException("Interrupted", e);
        } catch (RuntimeException e) {
            batch.close();
            throw e;
        }
        if (next >= rows.size()) {
            finish(context);
            return false;
        }
        return true;
    }

    /** Reads the document and reports what it found; never throws for a bad document. */
    private void read(StepContext context) throws StepException {
        String name = path.substring(path.lastIndexOf('/') + 1);
        String format = DocumentTypes.extension(name);
        String type = DocumentTypes.mediaType(name);
        DocumentLimits limits = DocumentRuntime.limits();
        long max = DocumentTypes.legacy(name) ? Math.min(limits.maxBytes(), limits.maxLegacyBytes()) : limits.maxBytes();
        Map<String, String> tags = Map.of();
        try {
            tags = store.tags(path);
        } catch (RuntimeException e) {
            // tags are a courtesy
        }
        Extraction x;
        try {
            if (type == null) {
                throw new DocumentException("Kilnmark does not read the text of this kind of file");
            }
            if (size > max) {
                throw LocalCopy.tooLarge(size, max);
            }
            try (LocalCopy copy = LocalCopy.of(store, path, max)) {
                x = DocumentRuntime.run(path, (AtomicBoolean cancelled) ->
                    TextExtractor.extract(copy.path(), name, size, limits, cancelled));
            }
        } catch (DocumentException e) {
            emit(context, assetUrn, withSource(DocumentEvents.unreadable(name, format, type, size, e.getMessage()),
                tags));
            return;
        } catch (IOException | RuntimeException e) {
            emit(context, assetUrn, withSource(DocumentEvents.unreadable(name, format, type, size,
                "it could not be fetched (" + e.getMessage() + ")"), tags));
            return;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new StepException("Interrupted while reading " + path, e);
        }
        Extraction.Part doc = x.document();
        List<Chunker.Chunk> chunks = keepText ? chunks(doc, limits) : List.of();
        chunks.forEach(c -> rows.add(new Row(assetUrn, c)));
        emit(context, assetUrn, withSource(DocumentEvents.attributes(doc, format, keepText, chunks.size(), x.notes()),
            tags));
        int n = 0;
        for (Extraction.Part a : x.attachments()) {
            n++;
            String urn = attachmentUrn(assetUrn, n, a.name());
            List<Chunker.Chunk> ac = keepText && a.problem() == null ? chunks(a, limits) : List.of();
            ac.forEach(c -> rows.add(new Row(urn, c)));
            Map<String, String> attrs = DocumentEvents.attributes(a, DocumentTypes.extension(a.name() == null ? ""
                : a.name()), keepText, ac.size(), List.of());
            attrs.put(DocumentEvents.PARENT, assetUrn);
            emit(context, urn, attrs);
        }
    }

    private static List<Chunker.Chunk> chunks(Extraction.Part p, DocumentLimits limits) {
        if (p.problem() != null) {
            return List.of();
        }
        return Chunker.chunks(p.text(), p.pages().stream().map(Extraction.Page::start).toList(),
            limits.chunkCharacters(), limits.chunkOverlap());
    }

    private Map<String, String> withSource(Map<String, String> attrs, Map<String, String> tags) {
        Map<String, String> a = new HashMap<>(attrs);
        if (owner != null) {
            a.put(DocumentEvents.OWNER, owner);
        }
        if (!tags.isEmpty()) {
            a.put(DocumentEvents.TAGS, DocumentEvents.jsonObject(tags));
        }
        return a;
    }

    private static void emit(StepContext context, String urn, Map<String, String> attrs) {
        context.metadata().emit(new MetadataEvent(urn, MetadataEvent.Kind.DOCUMENT, attrs, Instant.now()));
    }

    private void finish(StepContext context) {
        context.metadata().emit(new MetadataEvent(assetUrn, MetadataEvent.Kind.ROW_COUNT, Map.of("rows",
            Integer.toString(rows.size()), "complete", "true"), Instant.now()));
    }

    @Override
    public void close() {
        if (store != null) {
            store.cancel();
            try {
                store.close();
            } catch (IOException | RuntimeException e) {
                // closing a read; nothing to keep
            }
        }
    }
}

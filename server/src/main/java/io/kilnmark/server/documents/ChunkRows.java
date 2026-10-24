package io.kilnmark.server.documents;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.function.BiConsumer;

import org.apache.arrow.vector.IntVector;
import org.apache.arrow.vector.VarCharVector;
import org.apache.arrow.vector.VectorSchemaRoot;

import io.kilnmark.graph.store.DocumentRecords;
import io.kilnmark.kiln.Step;
import io.kilnmark.kiln.StepContext;
import io.kilnmark.kiln.StepException;
import io.kilnmark.kiln.plugins.documents.DocumentEvents;

/**
 * The last step of a document pipeline: takes chunk rows ({@link DocumentEvents#CHUNK_SCHEMA}) and
 * hands each, with its document's URN, to {@code into}; at the end, calls {@code done}. Steps that
 * work on chunks (embeddings, in round 3) go between the reader and this one.
 */
public final class ChunkRows implements Step {

    private final BiConsumer<String, DocumentRecords.Chunk> into;
    private final Runnable done;

    public ChunkRows(BiConsumer<String, DocumentRecords.Chunk> into, Runnable done) {
        this.into = into;
        this.done = done;
    }

    @Override
    public String type() {
        return "document-chunks";
    }

    @Override
    public void init(StepContext context) {
        // nothing to prepare
    }

    @Override
    public boolean process(StepContext context) throws StepException {
        VectorSchemaRoot b;
        try {
            b = context.input().take(context.allocator());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new StepException("Interrupted", e);
        }
        if (b == null) {
            done.run();
            return false;
        }
        try (b) {
            VarCharVector urn = (VarCharVector) b.getVector(DocumentEvents.ASSET_URN);
            IntVector seq = (IntVector) b.getVector(DocumentEvents.SEQUENCE);
            VarCharVector text = (VarCharVector) b.getVector(DocumentEvents.TEXT);
            IntVector page = (IntVector) b.getVector(DocumentEvents.PAGE);
            IntVector start = (IntVector) b.getVector(DocumentEvents.START);
            IntVector end = (IntVector) b.getVector(DocumentEvents.END);
            if (urn == null || seq == null || text == null || start == null || end == null) {
                throw new StepException("The step before " + context.stepName() + " does not send document chunks "
                    + "(columns " + List.of(DocumentEvents.ASSET_URN, DocumentEvents.SEQUENCE, DocumentEvents.TEXT)
                    + ")");
            }
            for (int i = 0; i < b.getRowCount(); i++) {
                into.accept(new String(urn.get(i), StandardCharsets.UTF_8), new DocumentRecords.Chunk(seq.get(i),
                    new String(text.get(i), StandardCharsets.UTF_8), page == null || page.isNull(i) ? null
                    : page.get(i), start.get(i), end.get(i)));
            }
        }
        return true;
    }
}

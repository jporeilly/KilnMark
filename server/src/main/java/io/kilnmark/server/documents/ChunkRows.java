package io.kilnmark.server.documents;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.function.BiConsumer;

import org.apache.arrow.vector.IntVector;
import org.apache.arrow.vector.VarCharVector;
import org.apache.arrow.vector.VectorSchemaRoot;

import io.kilnmark.graph.store.DocumentRecords;
import io.kilnmark.kiln.Node;
import io.kilnmark.kiln.NodeContext;
import io.kilnmark.kiln.NodeException;
import io.kilnmark.kiln.plugins.documents.DocumentEvents;

/**
 * The last node of a document pipeline: takes chunk rows ({@link DocumentEvents#CHUNK_SCHEMA}) and
 * hands each, with its document's URN, to {@code into}; at the end, calls {@code done}. Nodes that
 * work on chunks (embeddings, in round 3) go between the reader and this one.
 */
public final class ChunkRows implements Node {

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
    public void init(NodeContext context) {
        // nothing to prepare
    }

    @Override
    public boolean process(NodeContext context) throws NodeException {
        VectorSchemaRoot b;
        try {
            b = context.input().take(context.allocator());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new NodeException("Interrupted", e);
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
                throw new NodeException("The node before " + context.nodeName() + " does not send document chunks "
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

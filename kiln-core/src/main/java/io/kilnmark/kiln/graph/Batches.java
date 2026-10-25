package io.kilnmark.kiln.graph;

import java.util.ArrayList;
import java.util.List;

import org.apache.arrow.memory.ArrowBuf;
import org.apache.arrow.memory.BufferAllocator;
import org.apache.arrow.vector.VectorLoader;
import org.apache.arrow.vector.VectorSchemaRoot;
import org.apache.arrow.vector.VectorUnloader;
import org.apache.arrow.vector.ipc.message.ArrowRecordBatch;

/** Copies of Arrow batches, for an output that feeds several steps. */
final class Batches {

    private Batches() {
    }

    /**
     * A copy of {@code batch} whose memory is new and belongs to {@code into}: closing or changing
     * one never affects the other. The buffers are copied whole, so nested columns copy as well.
     */
    static VectorSchemaRoot copy(VectorSchemaRoot batch, BufferAllocator into) {
        VectorSchemaRoot copy = VectorSchemaRoot.create(batch.getSchema(), into);
        List<ArrowBuf> buffers = new ArrayList<>();
        try (ArrowRecordBatch from = new VectorUnloader(batch).getRecordBatch()) {
            for (ArrowBuf b : from.getBuffers()) {
                long length = b.readableBytes();
                ArrowBuf c = into.buffer(length);
                c.setBytes(0, b, b.readerIndex(), length);
                c.writerIndex(length);
                buffers.add(c);
            }
            try (ArrowRecordBatch to = new ArrowRecordBatch(from.getLength(), from.getNodes(), buffers,
                from.getBodyCompression(), from.getVariadicBufferCounts(), true)) {
                new VectorLoader(copy).load(to);
            }
            copy.setRowCount(batch.getRowCount());
            return copy;
        } catch (RuntimeException e) {
            copy.close();
            throw e;
        } finally {
            buffers.forEach(ArrowBuf::close); // the loaded vectors hold their own references
        }
    }
}

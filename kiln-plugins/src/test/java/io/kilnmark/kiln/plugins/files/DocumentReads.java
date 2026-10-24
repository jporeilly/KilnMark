package io.kilnmark.kiln.plugins.files;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import org.apache.arrow.vector.IntVector;
import org.apache.arrow.vector.VarCharVector;
import org.apache.arrow.vector.VectorSchemaRoot;

import io.kilnmark.kiln.MetadataEvent;
import io.kilnmark.kiln.Pipeline;
import io.kilnmark.kiln.RunResult;
import io.kilnmark.kiln.Runner;
import io.kilnmark.kiln.Step;
import io.kilnmark.kiln.StepContext;
import io.kilnmark.kiln.StepException;
import io.kilnmark.kiln.StepSpec;
import io.kilnmark.kiln.connect.AssetInfo;
import io.kilnmark.kiln.connect.Connector;
import io.kilnmark.kiln.connect.SourceConfig;
import io.kilnmark.kiln.plugins.documents.DocumentEvents;

/** Reads a document as a scan does, keeping the chunk rows and events. */
final class DocumentReads {

    private DocumentReads() {
    }

    record Chunk(String urn, int sequence, String text, Integer page, int start, int end) {
    }

    record Read(RunResult result, List<MetadataEvent> documents, List<Chunk> chunks) {
        /** The DOCUMENT event of the document itself. */
        Map<String, String> document() {
            return documents.get(0).attributes();
        }
    }

    static Read read(Connector connector, SourceConfig source, AssetInfo asset) throws InterruptedException {
        String urn = "urn:kilnmark:" + source.name() + ":" + asset.path();
        List<MetadataEvent> events = new CopyOnWriteArrayList<>();
        List<Chunk> chunks = new CopyOnWriteArrayList<>();
        StepSpec reader = connector.reader(source, asset, new Connector.ReadOptions(urn, 0, 1024));
        RunResult r = new Runner(64L << 20, 4).run(new Pipeline("read", List.of(reader,
            new StepSpec("collect", new Collector(chunks), Map.of()))), events::add);
        List<MetadataEvent> docs = new ArrayList<>(events.stream()
            .filter(e -> e.kind() == MetadataEvent.Kind.DOCUMENT).toList());
        return new Read(r, docs, chunks);
    }

    /** A sink keeping every chunk row. */
    static final class Collector implements Step {
        private final List<Chunk> into;

        Collector(List<Chunk> into) {
            this.into = into;
        }

        @Override
        public String type() {
            return "collect";
        }

        @Override
        public void init(StepContext context) {
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
                return false;
            }
            try (b) {
                VarCharVector urn = (VarCharVector) b.getVector(DocumentEvents.ASSET_URN);
                IntVector seq = (IntVector) b.getVector(DocumentEvents.SEQUENCE);
                VarCharVector text = (VarCharVector) b.getVector(DocumentEvents.TEXT);
                IntVector page = (IntVector) b.getVector(DocumentEvents.PAGE);
                IntVector start = (IntVector) b.getVector(DocumentEvents.START);
                IntVector end = (IntVector) b.getVector(DocumentEvents.END);
                for (int i = 0; i < b.getRowCount(); i++) {
                    into.add(new Chunk(new String(urn.get(i)), seq.get(i), new String(text.get(i),
                        java.nio.charset.StandardCharsets.UTF_8), page.isNull(i) ? null : page.get(i), start.get(i),
                        end.get(i)));
                }
            }
            return true;
        }
    }
}

package io.kilnmark.server.quality;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.apache.arrow.vector.FieldVector;
import org.apache.arrow.vector.VectorSchemaRoot;
import org.apache.arrow.vector.types.pojo.Field;

import io.kilnmark.kiln.Node;
import io.kilnmark.kiln.NodeContext;
import io.kilnmark.kiln.NodeException;
import io.kilnmark.kiln.plugins.profile.CellText;

/**
 * A Kiln node that evaluates rules on the Arrow batches streaming past, all rules in one pass.
 * Cells are turned into text only when a check asks for them. Batches go on to a next node if
 * there is one, so the node can sit in any pipeline.
 */
public final class RuleStreamNode implements Node {

    private final List<Rule> rules;
    private final Map<String, String> kinds;
    private final Long previousRows;
    private final int keepFailing;
    private final Instant now;
    private List<RowChecks.Check> checks;

    public RuleStreamNode(List<Rule> rules, Map<String, String> kinds, Long previousRows, int keepFailing, Instant now) {
        this.rules = List.copyOf(rules);
        this.kinds = Map.copyOf(kinds);
        this.previousRows = previousRows;
        this.keepFailing = keepFailing;
        this.now = now;
    }

    @Override
    public String type() {
        return "quality-rules";
    }

    @Override
    public void init(NodeContext context) {
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
            return false;
        }
        try {
            accept(batch);
        } catch (RuntimeException e) {
            batch.close();
            throw new NodeException("Checking rules failed: " + e.getMessage(), e);
        }
        if (context.hasOutput()) {
            try {
                context.output().put(batch);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new NodeException("Interrupted", e);
            }
        } else {
            batch.close();
        }
        return true;
    }

    void accept(VectorSchemaRoot batch) {
        List<FieldVector> vectors = batch.getFieldVectors();
        if (checks == null) {
            List<String> columns = batch.getSchema().getFields().stream().map(Field::getName).toList();
            checks = new ArrayList<>();
            for (Rule r : rules) {
                checks.add(RowChecks.of(r, columns, kinds, previousRows, now, keepFailing, "stream"));
            }
        }
        int n = batch.getRowCount();
        String[] cache = new String[vectors.size()];
        boolean[] read = new boolean[vectors.size()];
        for (int i = 0; i < n; i++) {
            java.util.Arrays.fill(read, false);
            int row = i;
            RowChecks.Row r = c -> {
                if (!read[c]) {
                    cache[c] = CellText.of(vectors.get(c), row);
                    read[c] = true;
                }
                return cache[c];
            };
            for (RowChecks.Check check : checks) {
                check.row(r);
            }
        }
    }

    /** The evaluation of each rule, in the order given; call after the pipeline finished. */
    public List<RowChecks.Evaluation> evaluations() {
        if (checks == null) {
            // Nothing was read, not even an empty batch: evaluate over no rows.
            List<RowChecks.Evaluation> out = new ArrayList<>();
            for (Rule r : rules) {
                out.add(RowChecks.of(r, List.of(), kinds, previousRows, now, keepFailing, "stream").finish());
            }
            return out;
        }
        return checks.stream().map(RowChecks.Check::finish).toList();
    }
}

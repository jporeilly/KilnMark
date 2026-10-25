package io.kilnmark.server.pipelines;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import io.kilnmark.kiln.MetadataEmitter;
import io.kilnmark.kiln.Pipeline;
import io.kilnmark.kiln.RunResult;
import io.kilnmark.kiln.Runner;
import io.kilnmark.kiln.StepSpec;
import io.kilnmark.kiln.graph.Edge;
import io.kilnmark.kiln.graph.GraphRunner;
import io.kilnmark.kiln.graph.GraphStep;
import io.kilnmark.kiln.graph.PipelineGraph;

/**
 * TEMPORARY: runs a pipeline graph that is a single line of steps (each joined {@code out} to
 * {@code in}) on the linear {@link Runner}, until kiln-core's graph runner is merged. Anything with
 * branches, several inputs or other ports fails at once with a plain message. Replace it in
 * {@link PipelineCompilers#runner}.
 */
final class InterimGraphRunner implements GraphRunner {

    private final long memoryLimitBytes;
    private final int rowSetCapacity;

    InterimGraphRunner(long memoryLimitBytes, int rowSetCapacity) {
        this.memoryLimitBytes = memoryLimitBytes;
        this.rowSetCapacity = rowSetCapacity;
    }

    @Override
    public RunResult run(PipelineGraph graph, MetadataEmitter metadata) throws InterruptedException {
        String problem = notALine(graph);
        if (problem != null) {
            Instant now = Instant.now();
            List<RunResult.StepResult> steps = graph.steps().stream()
                .map(s -> new RunResult.StepResult(s.id(), s.step().type(), 0, 0, null)).toList();
            graph.steps().forEach(s -> {
                try {
                    s.step().close();
                } catch (RuntimeException e) {
                    // never started
                }
            });
            return new RunResult(graph.name(), RunResult.Status.FAILED, now, now, steps, problem);
        }
        List<StepSpec> specs = new ArrayList<>();
        for (String id : graph.order()) {
            GraphStep s = graph.step(id);
            specs.add(new StepSpec(s.id(), s.step(), flat(s.config())));
        }
        return new Runner(memoryLimitBytes, rowSetCapacity).run(new Pipeline(graph.name(), specs), metadata);
    }

    /** Why this runner cannot run {@code graph}, or null when it is a line. */
    static String notALine(PipelineGraph graph) {
        List<String> order = graph.order();
        if (order == null) {
            return "The steps form a loop";
        }
        for (Edge e : graph.edges()) {
            if (!e.from().port().equals("out") || !e.to().port().equals("in")) {
                return "This server's engine runs pipelines whose steps follow one another in a line for now; "
                    + e + " uses another port";
            }
        }
        for (int i = 0; i < order.size(); i++) {
            List<Edge> in = graph.into(order.get(i));
            List<Edge> out = graph.outOf(order.get(i));
            boolean joined = i == 0 ? in.isEmpty() : in.size() == 1 && in.get(0).from().step().equals(order.get(i - 1));
            if (!joined || out.size() > 1 || (i + 1 < order.size()) != (out.size() == 1)) {
                return "This server's engine runs pipelines whose steps follow one another in a line for now; step "
                    + order.get(i) + " branches or joins";
            }
        }
        return null;
    }

    /** Settings as flat text, for steps written for the linear runner. */
    private static Map<String, String> flat(Map<String, Object> config) {
        Map<String, String> out = new LinkedHashMap<>();
        config.forEach((k, v) -> {
            if (v != null) {
                out.put(k, v instanceof List<?> l ? String.join(",", l.stream().map(String::valueOf).toList())
                    : String.valueOf(v));
            }
        });
        return out;
    }
}

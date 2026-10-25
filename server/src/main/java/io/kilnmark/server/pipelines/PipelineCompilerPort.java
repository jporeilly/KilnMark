package io.kilnmark.server.pipelines;

import java.util.List;
import java.util.Map;

import io.kilnmark.kiln.StepFactories;
import io.kilnmark.kiln.graph.PipelineGraph;

/**
 * SEAM: turns pipeline YAML ({@code kilnmark: pipeline/v1}, docs/design/pipelines.md section 2) into
 * a checked graph. The server talks to the compiler only through this interface.
 *
 * <p>Until kiln-core's {@code PipelineCompiler(StepFactories)} is merged, {@link InterimPipelineCompiler}
 * implements it. To swap in the real one, write an adapter implementing this interface over
 * {@code new PipelineCompiler(factories)} (mapping its problems to {@link Problem} and its steps to
 * {@link StepOutline}), and change {@link PipelineCompilers#produce} to return it. Nothing else in
 * the server names the interim compiler.
 */
public interface PipelineCompilerPort {

    /**
     * Checks a pipeline without creating any step: the YAML, every step's settings (through its
     * factory's {@code check}), the edges and ports, and each step's output columns where its
     * factory can tell ({@code outputSchema}).
     */
    Compilation check(String yaml, StepFactories factories);

    /**
     * Checks a pipeline and, when it has no problems, creates every step (through its factory's
     * {@code create}) and returns the graph to run in {@link Compilation#graph()}. Called by the
     * worker inside {@link RunContext#run}, so server step factories know whose run it is.
     */
    Compilation compile(String yaml, StepFactories factories);

    /**
     * One problem, in plain words. {@code line} and {@code column} count from 1, or are 0 when the
     * problem has no place in the text; {@code step} is the step's id, or null.
     */
    record Problem(int line, int column, String step, String message) {
    }

    /** A column leaving a step: its name and the engine's kind of value (text, int64, date, ...). */
    record Column(String name, String type) {
    }

    /**
     * A step as written: its id, type and kind, the line it starts on, its ports (output ports
     * for its settings) and the columns leaving each output port, or null for a port whose columns
     * cannot be known before a run.
     */
    record StepOutline(String id, String type, String kind, int line, List<String> inputs, List<String> outputs,
                       Map<String, List<Column>> columns) {
    }

    /** An edge: rows from {@code fromStep.fromPort} into {@code toStep.toPort}. */
    record EdgeOutline(String fromStep, String fromPort, String toStep, String toPort) {
    }

    /**
     * What compiling found. {@code graph} is null from {@link #check} and whenever there are problems.
     * {@code configs} holds each step's settings as written (for the server's own bookkeeping,
     * such as which assets a run reads and writes).
     */
    record Compilation(String name, String description, List<Problem> problems, List<StepOutline> steps,
                       List<EdgeOutline> edges, Map<String, Map<String, Object>> configs, PipelineGraph graph) {

        public Compilation {
            problems = List.copyOf(problems);
            steps = List.copyOf(steps);
            edges = List.copyOf(edges);
            configs = configs == null ? Map.of() : java.util.Collections.unmodifiableMap(
                new java.util.LinkedHashMap<>(configs));
        }

        public boolean valid() {
            return problems.isEmpty();
        }
    }
}

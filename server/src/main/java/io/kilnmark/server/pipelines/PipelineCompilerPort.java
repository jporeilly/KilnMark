package io.kilnmark.server.pipelines;

import java.util.List;
import java.util.Map;

import io.kilnmark.kiln.NodeFactories;
import io.kilnmark.kiln.graph.PipelineGraph;

/**
 * Turns pipeline YAML ({@code kilnmark: pipeline/v1}, docs/design/pipelines.md section 2) into
 * a checked graph. The server talks to the compiler only through this interface.
 *
 * <p>{@link KilnPipelineCompiler} implements it over kiln-core's {@code PipelineCompiler}; tests may
 * give the service another. {@link PipelineCompilers#compiler()} chooses the implementation.
 */
public interface PipelineCompilerPort {

    /**
     * Checks a pipeline without creating any node: the YAML, every node's settings (through its
     * factory's {@code check}), the edges and ports, and each node's output columns where its
     * factory can tell ({@code outputSchema}).
     */
    Compilation check(String yaml, NodeFactories factories);

    /**
     * Checks a pipeline and, when it has no problems, creates every node (through its factory's
     * {@code create}) and returns the graph to run in {@link Compilation#graph()}. Called by the
     * worker inside {@link RunContext#run}, so server node factories know whose run it is.
     */
    Compilation compile(String yaml, NodeFactories factories);

    /**
     * One problem, in plain words. {@code line} and {@code column} count from 1, or are 0 when the
     * problem has no place in the text; {@code node} is the node's id, or null.
     */
    record Problem(int line, int column, String node, String message) {
    }

    /** A column leaving a node: its name and the engine's kind of value (text, int64, date, ...). */
    record Column(String name, String type) {
    }

    /**
     * A node as written: its id, type and kind, the line it starts on, its ports (output ports
     * for its settings) and the columns leaving each output port, or null for a port whose columns
     * cannot be known before a run.
     */
    record NodeOutline(String id, String type, String kind, int line, List<String> inputs, List<String> outputs,
                       Map<String, List<Column>> columns) {
    }

    /** An edge: rows from {@code fromNode.fromPort} into {@code toNode.toPort}. */
    record EdgeOutline(String fromNode, String fromPort, String toNode, String toPort) {
    }

    /**
     * What compiling found. {@code notes} do not stop a run (columns not known until it runs, say).
     * {@code graph} is null from {@link #check} and whenever there are problems.
     * {@code configs} holds each node's settings as written (for the server's own bookkeeping,
     * such as which assets a run reads and writes).
     */
    record Compilation(String name, String description, List<Problem> problems, List<Problem> notes,
                       List<NodeOutline> nodes, List<EdgeOutline> edges, Map<String, Map<String, Object>> configs,
                       PipelineGraph graph) {

        public Compilation {
            problems = List.copyOf(problems);
            notes = notes == null ? List.of() : List.copyOf(notes);
            nodes = List.copyOf(nodes);
            edges = List.copyOf(edges);
            configs = configs == null ? Map.of() : java.util.Collections.unmodifiableMap(
                new java.util.LinkedHashMap<>(configs));
        }

        public boolean valid() {
            return problems.isEmpty();
        }
    }
}

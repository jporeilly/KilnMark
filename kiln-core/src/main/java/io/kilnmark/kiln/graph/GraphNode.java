package io.kilnmark.kiln.graph;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

import org.apache.arrow.vector.types.pojo.Schema;

import io.kilnmark.kiln.Node;

/**
 * One node of a pipeline graph: its id (unique in the pipeline), the node and its settings as
 * written in the YAML (maps, lists, strings, numbers and booleans).
 *
 * @param inputSchemas the columns arriving on each input port when they were known before the run,
 *                     keyed as {@link io.kilnmark.kiln.NodeFactory#outputSchema} receives them:
 *                     {@code in}, {@code main}, or {@code in[0]}, {@code in[1]}, … for a port that
 *                     takes many; a port whose columns are not known is missing. The node reads them
 *                     through {@link io.kilnmark.kiln.NodeContext#inputSchema(String)}.
 */
public record GraphNode(String id, Node node, Map<String, Object> config, Map<String, Schema> inputSchemas) {

    public GraphNode {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(node, "node");
        config = config == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(config));
        inputSchemas = inputSchemas == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(inputSchemas));
    }

    /** A node whose input columns are not known before the run. */
    public GraphNode(String id, Node node, Map<String, Object> config) {
        this(id, node, config, Map.of());
    }
}

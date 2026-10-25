package io.kilnmark.kiln.graph;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

import org.apache.arrow.vector.types.pojo.Schema;

import io.kilnmark.kiln.Step;

/**
 * One step of a pipeline graph: its id (unique in the pipeline), the step and its settings as
 * written in the YAML (maps, lists, strings, numbers and booleans).
 *
 * @param inputSchemas the columns arriving on each input port when they were known before the run,
 *                     keyed as {@link io.kilnmark.kiln.StepFactory#outputSchema} receives them:
 *                     {@code in}, {@code main}, or {@code in[0]}, {@code in[1]}, … for a port that
 *                     takes many; a port whose columns are not known is missing. The step reads them
 *                     through {@link io.kilnmark.kiln.StepContext#inputSchema(String)}.
 */
public record GraphStep(String id, Step step, Map<String, Object> config, Map<String, Schema> inputSchemas) {

    public GraphStep {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(step, "step");
        config = config == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(config));
        inputSchemas = inputSchemas == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(inputSchemas));
    }

    /** A step whose input columns are not known before the run. */
    public GraphStep(String id, Step step, Map<String, Object> config) {
        this(id, step, config, Map.of());
    }
}

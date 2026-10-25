package io.kilnmark.kiln.graph;

import java.util.Map;
import java.util.Objects;

import io.kilnmark.kiln.Step;

/**
 * One step of a pipeline graph: its id (unique in the pipeline), the step and its settings as
 * written in the YAML (maps, lists, strings, numbers and booleans).
 */
public record GraphStep(String id, Step step, Map<String, Object> config) {

    public GraphStep {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(step, "step");
        config = config == null ? Map.of() : java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(config));
    }
}

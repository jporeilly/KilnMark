package io.kilnmark.kiln;

import java.util.Map;
import java.util.Objects;

/** One step in a pipeline: its unique name, the step itself and its settings. */
public record StepSpec(String name, Step step, Map<String, String> settings) {

    public StepSpec {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(step, "step");
        settings = Map.copyOf(settings == null ? Map.of() : settings);
    }
}

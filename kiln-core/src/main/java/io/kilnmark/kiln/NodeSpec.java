package io.kilnmark.kiln;

import java.util.Map;
import java.util.Objects;

/** One node in a pipeline: its unique name, the node itself and its settings. */
public record NodeSpec(String name, Node node, Map<String, String> settings) {

    public NodeSpec {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(node, "node");
        settings = Map.copyOf(settings == null ? Map.of() : settings);
    }
}

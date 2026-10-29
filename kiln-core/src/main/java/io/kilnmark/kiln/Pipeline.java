package io.kilnmark.kiln;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;

/** Nodes run in a line: each node's output is the next node's input. */
public record Pipeline(String name, List<NodeSpec> nodes) {

    public Pipeline {
        Objects.requireNonNull(name, "name");
        nodes = List.copyOf(nodes);
        if (nodes.isEmpty()) {
            throw new IllegalArgumentException("A pipeline needs at least one node");
        }
        var seen = new HashSet<String>();
        for (NodeSpec s : nodes) {
            if (!seen.add(s.name())) {
                throw new IllegalArgumentException("Node name used twice: " + s.name());
            }
        }
    }
}

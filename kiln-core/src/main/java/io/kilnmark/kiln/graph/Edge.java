package io.kilnmark.kiln.graph;

import java.util.Objects;

/** Rows flow from an output port of one step to an input port of another. */
public record Edge(PortRef from, PortRef to) {

    public Edge {
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(to, "to");
    }

    @Override
    public String toString() {
        return from + " -> " + to;
    }
}

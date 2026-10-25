package io.kilnmark.kiln.graph;

import java.util.Objects;

/** One port of one step: {@code tidy.out}, {@code joined.rejected}, {@code joined.lookup}. */
public record PortRef(String step, String port) {

    public PortRef {
        Objects.requireNonNull(step, "step");
        Objects.requireNonNull(port, "port");
    }

    /** {@code id} for the default port {@code fallback}, or {@code id.port}. */
    public static PortRef parse(String text, String fallback) {
        String t = text == null ? "" : text.strip();
        if (t.isEmpty()) {
            throw new IllegalArgumentException("a step id is needed");
        }
        int dot = t.lastIndexOf('.');
        return dot < 0 ? new PortRef(t, fallback) : new PortRef(t.substring(0, dot), t.substring(dot + 1));
    }

    @Override
    public String toString() {
        return step + "." + port;
    }
}

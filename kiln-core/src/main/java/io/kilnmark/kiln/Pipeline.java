package io.kilnmark.kiln;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;

/** Steps run in a line: each step's output is the next step's input. */
public record Pipeline(String name, List<StepSpec> steps) {

    public Pipeline {
        Objects.requireNonNull(name, "name");
        steps = List.copyOf(steps);
        if (steps.isEmpty()) {
            throw new IllegalArgumentException("A pipeline needs at least one step");
        }
        var seen = new HashSet<String>();
        for (StepSpec s : steps) {
            if (!seen.add(s.name())) {
                throw new IllegalArgumentException("Step name used twice: " + s.name());
            }
        }
    }
}

package io.kilnmark.kiln.yaml;

import java.util.Comparator;
import java.util.Objects;

/**
 * Something wrong with a pipeline (or, among notes, worth knowing), in plain words, with where it
 * is in the YAML.
 *
 * @param line    the line, from 1
 * @param column  the column, from 1
 * @param stepId  the step it concerns, or null when it concerns the whole pipeline
 * @param message what is wrong and, where it helps, what to write instead
 */
public record Problem(int line, int column, String stepId, String message) {

    /** By line, then column. */
    public static final Comparator<Problem> BY_POSITION =
        Comparator.comparingInt(Problem::line).thenComparingInt(Problem::column);

    public Problem {
        Objects.requireNonNull(message, "message");
    }

    static Problem at(Position p, String stepId, String message) {
        Position at = p == null ? Position.START : p;
        return new Problem(at.line(), at.column(), stepId, message);
    }

    @Override
    public String toString() {
        return "line " + line + ", column " + column + ": " + message;
    }
}

package io.kilnmark.kiln.yaml;

/** A place in a pipeline's YAML: line and column, both counted from 1. */
public record Position(int line, int column) {

    /** The start of the file. */
    public static final Position START = new Position(1, 1);

    @Override
    public String toString() {
        return "line " + line + ", column " + column;
    }
}

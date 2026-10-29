package io.kilnmark.kiln;

/** What a node does in a pipeline, for the node catalogue and the canvas palette. */
public enum NodeKind {
    /** Reads rows from somewhere: no inputs. */
    SOURCE,
    /** Changes, combines or routes rows. */
    TRANSFORM,
    /** Checks rows against rules and stops or diverts them. */
    GATE,
    /** Writes rows somewhere: no outputs. */
    SINK
}

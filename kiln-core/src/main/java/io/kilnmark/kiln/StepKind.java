package io.kilnmark.kiln;

/** What a step does in a pipeline, for the step catalogue and the canvas palette. */
public enum StepKind {
    /** Reads rows from somewhere: no inputs. */
    SOURCE,
    /** Changes, combines or routes rows. */
    TRANSFORM,
    /** Checks rows against rules and stops or diverts them. */
    GATE,
    /** Writes rows somewhere: no outputs. */
    SINK
}

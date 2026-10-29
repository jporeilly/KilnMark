package io.kilnmark.kiln;

import java.io.Serial;

/** A node failed; the runner records the failure against the run and stops the pipeline. */
public class NodeException extends Exception {

    @Serial
    private static final long serialVersionUID = 1L;

    public NodeException(String message) {
        super(message);
    }

    public NodeException(String message, Throwable cause) {
        super(message, cause);
    }
}

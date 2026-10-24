package io.kilnmark.kiln;

import java.io.Serial;

/** A step failed; the runner records the failure against the run and stops the pipeline. */
public class StepException extends Exception {

    @Serial
    private static final long serialVersionUID = 1L;

    public StepException(String message) {
        super(message);
    }

    public StepException(String message, Throwable cause) {
        super(message, cause);
    }
}

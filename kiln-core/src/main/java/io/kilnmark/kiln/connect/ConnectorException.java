package io.kilnmark.kiln.connect;

import java.io.Serial;

/** A source could not be reached or read. The message is shown to people, so make it actionable. */
public class ConnectorException extends Exception {

    @Serial
    private static final long serialVersionUID = 1L;

    public ConnectorException(String message) {
        super(message);
    }

    public ConnectorException(String message, Throwable cause) {
        super(message, cause);
    }
}

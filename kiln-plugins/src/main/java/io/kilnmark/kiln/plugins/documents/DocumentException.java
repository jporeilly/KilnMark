package io.kilnmark.kiln.plugins.documents;

import java.io.Serial;

/**
 * A document could not be read. The message completes "Kilnmark could not read this document:"
 * in plain English and is shown to people, so it says what happened and, where it helps, what to do.
 */
public class DocumentException extends Exception {

    @Serial
    private static final long serialVersionUID = 1L;

    public DocumentException(String reason) {
        super(reason);
    }

    public DocumentException(String reason, Throwable cause) {
        super(reason, cause);
    }
}

package io.kilnmark.kiln.plugins.documents;

import java.io.Serial;

/**
 * A document could not be read. The message completes "Kilnmark could not read this document:"
 * in plain English and is shown to people, so it says what happened and, where it helps, what to do.
 */
public class DocumentException extends Exception {

    @Serial
    private static final long serialVersionUID = 1L;

    private final boolean passing;

    public DocumentException(String reason) {
        this(reason, false, null);
    }

    public DocumentException(String reason, Throwable cause) {
        this(reason, false, cause);
    }

    /**
     * @param passing true when the failure says nothing about the document itself (the server was
     *                busy, the read ran out of time, the scan was stopped, the source could not be
     *                reached): what an earlier scan stored about it is then kept
     */
    public DocumentException(String reason, boolean passing, Throwable cause) {
        super(reason, cause);
        this.passing = passing;
    }

    /** Whether the failure is passing rather than about the document; see the constructor. */
    public boolean passing() {
        return passing;
    }
}

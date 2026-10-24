package io.kilnmark.server.ai;

/**
 * A model call that did not happen or did not succeed. The message is plain English for people,
 * says what to do next, and never holds an API key.
 */
public final class AiException extends RuntimeException {
    @java.io.Serial
    private static final long serialVersionUID = 1L;

    /** Why there is no answer. */
    public enum Kind {
        /** No profile is set for the feature: it is off and its fallback applies. */
        UNAVAILABLE,
        /** The egress rule forbids this model from seeing this data. */
        REFUSED,
        /** The model or its server failed, or the answer was unusable. */
        FAILED,
        TIMEOUT,
        /** The profile's concurrency limit stayed full for the whole timeout. */
        BUSY,
        CANCELLED
    }

    private final Kind kind;

    public AiException(Kind kind, String message) {
        super(message, null, false, false);
        this.kind = kind;
    }

    public Kind kind() {
        return kind;
    }

    /** The outcome stored in the usage record. */
    String outcome() {
        return switch (kind) {
            case UNAVAILABLE, FAILED -> "failed";
            case REFUSED -> "refused";
            case TIMEOUT -> "timeout";
            case BUSY -> "busy";
            case CANCELLED -> "cancelled";
        };
    }
}

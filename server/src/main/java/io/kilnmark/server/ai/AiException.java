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
    private final boolean unreachable;

    public AiException(Kind kind, String message) {
        this(kind, message, false);
    }

    /** {@code unreachable}: the model's server could not be reached at all. */
    public AiException(Kind kind, String message, boolean unreachable) {
        super(message, null, false, false);
        this.kind = kind;
        this.unreachable = unreachable;
    }

    public Kind kind() {
        return kind;
    }

    public boolean unreachable() {
        return unreachable;
    }

    /**
     * Words safe to show anyone who can search: no address, model name or server reply. Refusals
     * by the egress rule name only the source and the profile, so they are shown as they are.
     */
    public String publicMessage() {
        return switch (kind) {
            case REFUSED -> getMessage();
            case UNAVAILABLE -> "No model is set for this.";
            case CANCELLED -> "The call was cancelled.";
            default -> "The AI model is unavailable right now. An admin can see why under AI usage.";
        };
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

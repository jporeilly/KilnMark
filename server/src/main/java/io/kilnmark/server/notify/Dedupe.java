package io.kilnmark.server.notify;

/**
 * Whether an event is worth an email, given what has already been said about the same problem
 * (its dedupe key). A rule failing on every scan is mailed once, when it starts failing, and again
 * only if it gets worse (a warning that becomes a failure); then once when it recovers, at the
 * severity of the failure, so everyone who heard of the failure hears of the recovery. A recovery
 * nobody heard of the failure for is not mailed. One-off events are mailed once per key, which only
 * drops repeats of the very same event (a quality evaluation run twice, say).
 */
public final class Dedupe {

    private Dedupe() {
    }

    /** What has been said about one problem: whether it is open, and at what severity it was mailed. */
    public record State(boolean open, Severity severity) {
    }

    /**
     * @param send     whether to mail the event
     * @param severity the severity to mail it at (a recovery takes its failure's)
     * @param next     the state to keep; null to keep none
     */
    public record Decision(boolean send, Severity severity, State next) {
    }

    /** Decides for an event of {@code kind} at {@code severity}, the problem being in state {@code current} (null: never seen). */
    public static Decision decide(NotificationKind kind, Severity severity, State current) {
        return switch (kind.repeat()) {
            case OPENS -> {
                if (current == null || !current.open()) {
                    yield new Decision(true, severity, new State(true, severity));
                }
                if (severity.compareTo(current.severity()) > 0) {
                    yield new Decision(true, severity, new State(true, severity));
                }
                yield new Decision(false, current.severity(), current);
            }
            case CLOSES -> current != null && current.open()
                ? new Decision(true, current.severity(), new State(false, current.severity()))
                : new Decision(false, severity, current);
            case ONCE -> current == null
                ? new Decision(true, severity, new State(false, severity))
                : new Decision(false, severity, current);
        };
    }

    /** A problem that ended without an email of its own (a scan that succeeds again): closes it quietly. */
    public static State clear(State current) {
        return current == null || !current.open() ? current : new State(false, current.severity());
    }
}

package io.kilnmark.kiln.flow;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

/**
 * Decides which steps of a flow run next (docs/design/flows.md, "How a flow runs"). It holds no
 * threads and touches no database: whoever runs the flow asks {@link #ready()} what to start, runs
 * those steps, and reports each with {@link #finish}.
 *
 * <p>A step with no path into it starts at once. A step with paths into it waits until every one
 * is settled (the step it comes from has ended or been skipped), then starts if at least one path
 * is taken, and is skipped if none is; a skipped step settles its own paths as not taken, so a
 * whole branch can be skipped. A path is taken when the step it leaves ended the way the path asks:
 * {@code success}, {@code failure}, {@code always} (succeeded or failed), or a condition, which
 * needs the step to have succeeded and the condition to be true.
 */
public final class FlowEngine {

    /** Where a step is. */
    public enum State { PENDING, RUNNING, SUCCEEDED, FAILED, CANCELLED, SKIPPED }

    /** What makes a path taken. */
    public enum Kind { SUCCESS, FAILURE, ALWAYS, CONDITION }

    /**
     * A path between two steps. {@code condition} is read only for {@link Kind#CONDITION}; it gets
     * the value called a name (see {@link #value}) and answers true, false or null for unknown.
     */
    public record Path(String from, String to, Kind kind, Condition condition) {
        public Path {
            Objects.requireNonNull(from, "from");
            Objects.requireNonNull(to, "to");
            Objects.requireNonNull(kind, "kind");
            if (kind == Kind.CONDITION) {
                Objects.requireNonNull(condition, "condition");
            }
        }
    }

    /** A condition over the values the flow has so far. */
    @FunctionalInterface
    public interface Condition {
        Boolean test(Function<String, Object> values);
    }

    /** How a flow ended. */
    public enum Outcome { SUCCEEDED, FAILED, CANCELLED }

    private final List<String> steps;
    private final List<Path> paths;
    private final Map<String, Object> parameters;
    private final Map<String, State> states = new LinkedHashMap<>();
    private final Map<String, Map<String, Object>> results = new LinkedHashMap<>();
    private boolean cancelled;      // a person asked the flow to stop
    private boolean stepCancelled;  // a step ended cancelled
    private boolean haltedFailed;
    private String haltMessage;

    /**
     * @param steps      the step ids
     * @param paths      the paths between them; none may name a step that is not in {@code steps}
     * @param parameters the flow's parameters by name
     */
    public FlowEngine(List<String> steps, List<Path> paths, Map<String, Object> parameters) {
        this.steps = List.copyOf(steps);
        this.paths = List.copyOf(paths);
        this.parameters = parameters == null ? Map.of() : Map.copyOf(parameters);
        for (String s : this.steps) {
            states.put(s, State.PENDING);
        }
        for (Path p : this.paths) {
            if (!states.containsKey(p.from()) || !states.containsKey(p.to())) {
                throw new IllegalArgumentException("Path " + p.from() + " -> " + p.to() + " names a step that is not in the flow");
            }
        }
    }

    public State state(String step) {
        State s = states.get(step);
        if (s == null) {
            throw new IllegalArgumentException("No step called " + step);
        }
        return s;
    }

    /** What a finished step reported, by field name; empty before it ends. */
    public Map<String, Object> results(String step) {
        return results.getOrDefault(step, Map.of());
    }

    /**
     * The value a condition reads: a parameter by its name, or {@code step.field} of a step that
     * has ended (its {@code status} is always there); null when unknown.
     */
    public Object value(String name) {
        if (parameters.containsKey(name)) {
            return parameters.get(name);
        }
        int dot = name.indexOf('.');
        if (dot < 0) {
            return null;
        }
        String step = name.substring(0, dot);
        String field = name.substring(dot + 1);
        State s = states.get(step);
        if (s == null || s == State.PENDING || s == State.RUNNING || s == State.SKIPPED) {
            return null;
        }
        if (field.equals("status")) {
            return s.name().toLowerCase();
        }
        return results.getOrDefault(step, Map.of()).get(field);
    }

    /**
     * Marks as running, and returns, every step that can start now, after skipping the steps whose
     * paths are all settled and none taken. Call it again after each {@link #finish}.
     */
    public List<String> ready() {
        List<String> started = new ArrayList<>();
        if (cancelled || haltMessage != null) {
            return started;
        }
        boolean changed = true;
        while (changed) {
            changed = false;
            for (String step : steps) {
                if (states.get(step) != State.PENDING) {
                    continue;
                }
                List<Path> into = paths.stream().filter(p -> p.to().equals(step)).toList();
                if (!into.isEmpty() && !into.stream().allMatch(this::settled)) {
                    continue;
                }
                if (into.isEmpty() || into.stream().anyMatch(this::taken)) {
                    states.put(step, State.RUNNING);
                    started.add(step);
                } else {
                    states.put(step, State.SKIPPED);
                }
                changed = true;
            }
        }
        return started;
    }

    /**
     * Records how a running step ended.
     *
     * @param outcome SUCCEEDED, FAILED or CANCELLED
     * @param values  what it reported, by field name (numbers, text, booleans; null for unknown)
     */
    public void finish(String step, State outcome, Map<String, Object> values) {
        if (state(step) != State.RUNNING) {
            throw new IllegalStateException("Step " + step + " is " + state(step) + ", not running");
        }
        if (outcome != State.SUCCEEDED && outcome != State.FAILED && outcome != State.CANCELLED) {
            throw new IllegalArgumentException("A step ends as succeeded, failed or cancelled, not " + outcome);
        }
        states.put(step, outcome);
        results.put(step, values == null ? Map.of() : Map.copyOf(withoutNulls(values)));
        if (outcome == State.CANCELLED) {
            stepCancelled = true;
        }
    }

    private static Map<String, Object> withoutNulls(Map<String, Object> m) {
        Map<String, Object> out = new LinkedHashMap<>();
        m.forEach((k, v) -> {
            if (v != null) {
                out.put(k, v);
            }
        });
        return out;
    }

    /** A person asked the flow to stop: nothing more starts, and what has not started is skipped. */
    public void cancel() {
        cancelled = true;
        skipPending();
    }

    /**
     * A {@code stop} step ran: nothing more starts, and what has not started is skipped. Steps still
     * running are left to the caller to cancel.
     *
     * @param failed whether the flow counts as failed
     */
    public void halt(boolean failed, String message) {
        if (haltMessage == null) {
            haltedFailed = failed;
            haltMessage = message == null ? "" : message;
        }
        skipPending();
    }

    private void skipPending() {
        states.replaceAll((k, v) -> v == State.PENDING ? State.SKIPPED : v);
    }

    /** Whether the flow was stopped by a {@code stop} step. */
    public boolean halted() {
        return haltMessage != null;
    }

    /** The message of the {@code stop} step that ended the flow, or null. */
    public String haltMessage() {
        return haltMessage;
    }

    /** Whether nothing is waiting or running. */
    public boolean done() {
        return states.values().stream().noneMatch(s -> s == State.PENDING || s == State.RUNNING);
    }

    /** The steps that are running. */
    public List<String> running() {
        return steps.stream().filter(s -> states.get(s) == State.RUNNING).toList();
    }

    /**
     * Whether a failed step's failure was dealt with: a path leaves it that takes a failure
     * ({@code failure} or {@code always}).
     */
    public boolean handled(String step) {
        return states.get(step) == State.FAILED
            && paths.stream().anyMatch(p -> p.from().equals(step)
            && (p.kind() == Kind.FAILURE || p.kind() == Kind.ALWAYS));
    }

    /**
     * How the flow ended, once {@link #done}: cancelled if asked to stop; otherwise as a {@code stop}
     * step that ran said; otherwise cancelled if a step was cancelled, failed if a step failed with no
     * path to deal with it, and succeeded if none.
     */
    public Outcome outcome() {
        if (!done()) {
            throw new IllegalStateException("The flow has not finished");
        }
        if (cancelled) {
            return Outcome.CANCELLED;
        }
        if (haltMessage != null) {
            // The steps a stop step cancelled do not make the flow cancelled: the stop decides.
            return haltedFailed ? Outcome.FAILED : Outcome.SUCCEEDED;
        }
        if (stepCancelled) {
            return Outcome.CANCELLED;
        }
        for (String s : steps) {
            if (states.get(s) == State.FAILED && !handled(s)) {
                return Outcome.FAILED;
            }
        }
        return Outcome.SUCCEEDED;
    }

    /** Whether a path is settled: the step it leaves has ended or been skipped. */
    private boolean settled(Path p) {
        State s = states.get(p.from());
        return s != State.PENDING && s != State.RUNNING;
    }

    private boolean taken(Path p) {
        State s = states.get(p.from());
        return switch (p.kind()) {
            case SUCCESS -> s == State.SUCCEEDED;
            case FAILURE -> s == State.FAILED;
            case ALWAYS -> s == State.SUCCEEDED || s == State.FAILED;
            case CONDITION -> s == State.SUCCEEDED && Boolean.TRUE.equals(p.condition().test(this::value));
        };
    }
}

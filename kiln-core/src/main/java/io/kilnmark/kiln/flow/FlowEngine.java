package io.kilnmark.kiln.flow;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

/**
 * Decides which nodes of a flow run next (docs/design/flows.md, "How a flow runs"). It holds no
 * threads and touches no database: whoever runs the flow asks {@link #ready()} what to start, runs
 * those nodes, and reports each with {@link #finish}.
 *
 * <p>A node with no connection into it starts at once. A node with connections into it waits until every one
 * is settled (the node it comes from has ended or been skipped), then starts if at least one connection
 * is taken, and is skipped if none is; a skipped node settles its own connections as not taken, so a
 * whole branch can be skipped. A connection is taken when the node it leaves ended the way the connection asks:
 * {@code success}, {@code failure}, {@code always} (succeeded or failed), or a condition, which
 * needs the node to have succeeded and the condition to be true.
 */
public final class FlowEngine {

    /** Where a node is. */
    public enum State { PENDING, RUNNING, SUCCEEDED, FAILED, CANCELLED, SKIPPED }

    /** What makes a connection taken. */
    public enum Kind { SUCCESS, FAILURE, ALWAYS, CONDITION }

    /**
     * A connection between two nodes. {@code condition} is read only for {@link Kind#CONDITION}; it gets
     * the value called a name (see {@link #value}) and answers true, false or null for unknown.
     */
    public record Connection(String from, String to, Kind kind, Condition condition) {
        public Connection {
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

    private final List<String> nodes;
    private final List<Connection> connections;
    private final Map<String, Object> parameters;
    private final int maxParallel;
    private final Set<String> neverHeld;
    private final Map<String, State> states = new LinkedHashMap<>();
    private final Map<String, Map<String, Object>> results = new LinkedHashMap<>();
    private boolean cancelled;      // a person asked the flow to stop
    private boolean nodeCancelled;  // a node ended cancelled
    private boolean haltedFailed;
    private String haltMessage;

    /**
     * @param nodes       the node ids
     * @param connections the connections between them; none may name a node that is not in {@code nodes}
     * @param parameters  the flow's parameters by name
     */
    public FlowEngine(List<String> nodes, List<Connection> connections, Map<String, Object> parameters) {
        this(nodes, connections, parameters, 0, Set.of());
    }

    /**
     * A flow with a limit on how many of its nodes run at once ({@code max_parallel}, ADR 0040): a node
     * ready beyond it stays pending, and nodes start in the order {@code nodes} gives as others end.
     *
     * @param maxParallel the most nodes running at once, or 0 for no limit
     * @param neverHeld   nodes started however many are running ({@code stop} nodes, so a flow can always end)
     */
    public FlowEngine(List<String> nodes, List<Connection> connections, Map<String, Object> parameters,
                      int maxParallel, Set<String> neverHeld) {
        if (maxParallel < 0) {
            throw new IllegalArgumentException("maxParallel is 0 (no limit) or more");
        }
        this.maxParallel = maxParallel;
        this.neverHeld = Set.copyOf(neverHeld);
        this.nodes = List.copyOf(nodes);
        this.connections = List.copyOf(connections);
        this.parameters = parameters == null ? Map.of() : Map.copyOf(parameters);
        for (String s : this.nodes) {
            states.put(s, State.PENDING);
        }
        for (Connection p : this.connections) {
            if (!states.containsKey(p.from()) || !states.containsKey(p.to())) {
                throw new IllegalArgumentException("Connection " + p.from() + " -> " + p.to() + " names a node that is not in the flow");
            }
        }
    }

    public State state(String node) {
        State s = states.get(node);
        if (s == null) {
            throw new IllegalArgumentException("No node called " + node);
        }
        return s;
    }

    /** What a finished node reported, by field name; empty before it ends. */
    public Map<String, Object> results(String node) {
        return results.getOrDefault(node, Map.of());
    }

    /**
     * The value a condition reads: a parameter by its name, or {@code node.field} of a node that
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
        String node = name.substring(0, dot);
        String field = name.substring(dot + 1);
        State s = states.get(node);
        if (s == null || s == State.PENDING || s == State.RUNNING || s == State.SKIPPED) {
            return null;
        }
        if (field.equals("status")) {
            return s.name().toLowerCase();
        }
        return results.getOrDefault(node, Map.of()).get(field);
    }

    /**
     * Marks as running, and returns, every node that can start now, after skipping the nodes whose
     * connections are all settled and none taken. Call it again after each {@link #finish}. With a
     * limit, a node that could start while the limit's worth are running stays pending (held) until
     * one ends, unless it is never held; nodes are started in the order they were given.
     */
    public List<String> ready() {
        List<String> started = new ArrayList<>();
        if (cancelled || haltMessage != null) {
            return started;
        }
        // First every skip, which can settle further connections; starting a node settles none.
        boolean changed = true;
        while (changed) {
            changed = false;
            for (String node : nodes) {
                List<Connection> into = intoIfSettled(node);
                if (into != null && !into.isEmpty() && into.stream().noneMatch(this::taken)) {
                    states.put(node, State.SKIPPED);
                    changed = true;
                }
            }
        }
        // Then what can start, in the order the nodes were given, while the limit allows.
        long running = states.values().stream().filter(s -> s == State.RUNNING).count();
        for (String node : nodes) {
            if (intoIfSettled(node) == null) {
                continue;
            }
            if (maxParallel > 0 && running >= maxParallel && !neverHeld.contains(node)) {
                continue; // held: it starts when a running node ends
            }
            states.put(node, State.RUNNING);
            started.add(node);
            running++;
        }
        return started;
    }

    /**
     * Records how a running node ended.
     *
     * @param outcome SUCCEEDED, FAILED or CANCELLED
     * @param values  what it reported, by field name (numbers, text, booleans; null for unknown)
     */
    public void finish(String node, State outcome, Map<String, Object> values) {
        if (state(node) != State.RUNNING) {
            throw new IllegalStateException("Node " + node + " is " + state(node) + ", not running");
        }
        if (outcome != State.SUCCEEDED && outcome != State.FAILED && outcome != State.CANCELLED) {
            throw new IllegalArgumentException("A node ends as succeeded, failed or cancelled, not " + outcome);
        }
        states.put(node, outcome);
        results.put(node, values == null ? Map.of() : Map.copyOf(withoutNulls(values)));
        if (outcome == State.CANCELLED) {
            nodeCancelled = true;
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
     * A {@code stop} node ran: nothing more starts, and what has not started is skipped. Nodes still
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

    /** Whether the flow was stopped by a {@code stop} node. */
    public boolean halted() {
        return haltMessage != null;
    }

    /** The message of the {@code stop} node that ended the flow, or null. */
    public String haltMessage() {
        return haltMessage;
    }

    /** Whether nothing is waiting or running. */
    public boolean done() {
        return states.values().stream().noneMatch(s -> s == State.PENDING || s == State.RUNNING);
    }

    /** The nodes that are running. */
    public List<String> running() {
        return nodes.stream().filter(s -> states.get(s) == State.RUNNING).toList();
    }

    /**
     * Whether a failed node's failure was dealt with: a connection leaves it that takes a failure
     * ({@code failure} or {@code always}).
     */
    public boolean handled(String node) {
        return states.get(node) == State.FAILED
            && connections.stream().anyMatch(p -> p.from().equals(node)
            && (p.kind() == Kind.FAILURE || p.kind() == Kind.ALWAYS));
    }

    /**
     * How the flow ended, once {@link #done}: cancelled if asked to stop; otherwise as a {@code stop}
     * node that ran said; otherwise cancelled if a node was cancelled, failed if a node failed with no
     * connection to deal with it, and succeeded if none.
     */
    public Outcome outcome() {
        if (!done()) {
            throw new IllegalStateException("The flow has not finished");
        }
        if (cancelled) {
            return Outcome.CANCELLED;
        }
        if (haltMessage != null) {
            // The nodes a stop node cancelled do not make the flow cancelled: the stop decides.
            return haltedFailed ? Outcome.FAILED : Outcome.SUCCEEDED;
        }
        if (nodeCancelled) {
            return Outcome.CANCELLED;
        }
        for (String s : nodes) {
            if (states.get(s) == State.FAILED && !handled(s)) {
                return Outcome.FAILED;
            }
        }
        return Outcome.SUCCEEDED;
    }

    /** The connections into a pending node whose connections in are all settled; null for any other node. */
    private List<Connection> intoIfSettled(String node) {
        if (states.get(node) != State.PENDING) {
            return null;
        }
        List<Connection> into = connections.stream().filter(p -> p.to().equals(node)).toList();
        return into.stream().allMatch(this::settled) ? into : null;
    }

    /** Whether a connection is settled: the node it leaves has ended or been skipped. */
    private boolean settled(Connection p) {
        State s = states.get(p.from());
        return s != State.PENDING && s != State.RUNNING;
    }

    private boolean taken(Connection p) {
        State s = states.get(p.from());
        return switch (p.kind()) {
            case SUCCESS -> s == State.SUCCEEDED;
            case FAILURE -> s == State.FAILED;
            case ALWAYS -> s == State.SUCCEEDED || s == State.FAILED;
            case CONDITION -> s == State.SUCCEEDED && Boolean.TRUE.equals(p.condition().test(this::value));
        };
    }
}

package io.kilnmark.kiln.graph;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * A pipeline as a directed acyclic graph of steps joined port to port (docs/design/pipelines.md,
 * ADR 0022). One output port may feed several input ports; an input port takes one edge, except
 * a port declared as taking many (union's {@code in}).
 */
public record PipelineGraph(String name, List<GraphStep> steps, List<Edge> edges) {

    public PipelineGraph {
        Objects.requireNonNull(name, "name");
        steps = List.copyOf(steps);
        edges = List.copyOf(edges);
    }

    /** The step with this id. */
    public GraphStep step(String id) {
        for (GraphStep s : steps) {
            if (s.id().equals(id)) {
                return s;
            }
        }
        throw new IllegalArgumentException("No step called " + id);
    }

    /** Edges arriving at a step. */
    public List<Edge> into(String id) {
        return edges.stream().filter(e -> e.to().step().equals(id)).toList();
    }

    /** Edges leaving a step. */
    public List<Edge> outOf(String id) {
        return edges.stream().filter(e -> e.from().step().equals(id)).toList();
    }

    /**
     * Problems with the shape of the graph, in plain words; empty when it can run. Checks ids,
     * that every edge joins existing steps, that the graph has no cycle and at least one step.
     * Ports are checked against each step's declared ports by whoever built the graph from
     * {@link io.kilnmark.kiln.StepFactory step factories}.
     */
    public List<String> check() {
        List<String> problems = new ArrayList<>();
        if (steps.isEmpty()) {
            problems.add("A pipeline needs at least one step");
            return problems;
        }
        Set<String> ids = new HashSet<>();
        for (GraphStep s : steps) {
            if (!ids.add(s.id())) {
                problems.add("Step id used twice: " + s.id());
            }
        }
        for (Edge e : edges) {
            if (!ids.contains(e.from().step())) {
                problems.add("Edge " + e + " starts at a step that does not exist");
            }
            if (!ids.contains(e.to().step())) {
                problems.add("Edge " + e + " ends at a step that does not exist");
            }
            if (e.from().step().equals(e.to().step())) {
                problems.add("Step " + e.from().step() + " feeds itself");
            }
        }
        if (problems.isEmpty() && order() == null) {
            problems.add("The steps form a loop: a pipeline must flow one way");
        }
        return problems;
    }

    /** Step ids in an order where every step comes after the steps feeding it; null if there is a cycle. */
    public List<String> order() {
        Map<String, Integer> waiting = new HashMap<>();
        for (GraphStep s : steps) {
            waiting.put(s.id(), 0);
        }
        for (Edge e : edges) {
            waiting.merge(e.to().step(), 1, Integer::sum);
        }
        ArrayDeque<String> ready = new ArrayDeque<>();
        for (GraphStep s : steps) {
            if (waiting.get(s.id()) == 0) {
                ready.add(s.id());
            }
        }
        List<String> out = new ArrayList<>();
        while (!ready.isEmpty()) {
            String id = ready.poll();
            out.add(id);
            for (Edge e : outOf(id)) {
                if (waiting.merge(e.to().step(), -1, Integer::sum) == 0) {
                    ready.add(e.to().step());
                }
            }
        }
        return out.size() == steps.size() ? out : null;
    }
}

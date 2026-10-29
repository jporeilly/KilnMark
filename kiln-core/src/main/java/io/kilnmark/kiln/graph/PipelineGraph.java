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
 * A pipeline as a directed acyclic graph of nodes joined port to port (docs/design/pipelines.md,
 * ADR 0022). One output port may feed several input ports; an input port takes one edge, except
 * a port declared as taking many (union's {@code in}).
 */
public record PipelineGraph(String name, List<GraphNode> nodes, List<Edge> edges) {

    public PipelineGraph {
        Objects.requireNonNull(name, "name");
        nodes = List.copyOf(nodes);
        edges = List.copyOf(edges);
    }

    /** The node with this id. */
    public GraphNode node(String id) {
        for (GraphNode s : nodes) {
            if (s.id().equals(id)) {
                return s;
            }
        }
        throw new IllegalArgumentException("No node called " + id);
    }

    /** Edges arriving at a node. */
    public List<Edge> into(String id) {
        return edges.stream().filter(e -> e.to().node().equals(id)).toList();
    }

    /** Edges leaving a node. */
    public List<Edge> outOf(String id) {
        return edges.stream().filter(e -> e.from().node().equals(id)).toList();
    }

    /**
     * Problems with the shape of the graph, in plain words; empty when it can run. Checks ids,
     * that every edge joins existing nodes, that the graph has no cycle and at least one node.
     * Ports are checked against each node's declared ports by whoever built the graph from
     * {@link io.kilnmark.kiln.NodeFactory node factories}.
     */
    public List<String> check() {
        List<String> problems = new ArrayList<>();
        if (nodes.isEmpty()) {
            problems.add("A pipeline needs at least one node");
            return problems;
        }
        Set<String> ids = new HashSet<>();
        for (GraphNode s : nodes) {
            if (!ids.add(s.id())) {
                problems.add("Node id used twice: " + s.id());
            }
        }
        for (Edge e : edges) {
            if (!ids.contains(e.from().node())) {
                problems.add("Edge " + e + " starts at a node that does not exist");
            }
            if (!ids.contains(e.to().node())) {
                problems.add("Edge " + e + " ends at a node that does not exist");
            }
            if (e.from().node().equals(e.to().node())) {
                problems.add("Node " + e.from().node() + " feeds itself");
            }
        }
        if (problems.isEmpty() && order() == null) {
            problems.add("The nodes form a loop: a pipeline must flow one way");
        }
        return problems;
    }

    /** Node ids in an order where every node comes after the nodes feeding it; null if there is a cycle. */
    public List<String> order() {
        Map<String, Integer> waiting = new HashMap<>();
        for (GraphNode s : nodes) {
            waiting.put(s.id(), 0);
        }
        for (Edge e : edges) {
            waiting.merge(e.to().node(), 1, Integer::sum);
        }
        ArrayDeque<String> ready = new ArrayDeque<>();
        for (GraphNode s : nodes) {
            if (waiting.get(s.id()) == 0) {
                ready.add(s.id());
            }
        }
        List<String> out = new ArrayList<>();
        while (!ready.isEmpty()) {
            String id = ready.poll();
            out.add(id);
            for (Edge e : outOf(id)) {
                if (waiting.merge(e.to().node(), -1, Integer::sum) == 0) {
                    ready.add(e.to().node());
                }
            }
        }
        return out.size() == nodes.size() ? out : null;
    }
}

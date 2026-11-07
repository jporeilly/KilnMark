package io.kilnmark.server.flows;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import io.kilnmark.kiln.flow.FlowEngine;
import io.kilnmark.kiln.yaml.FlowDefinition;

/**
 * How a flow run's next try carries on from the try before (ADR 0053, decision 7): which nodes keep
 * what they did, which run again, and what, if anything, makes trying again unsafe or pointless. Pure:
 * what it needs about the pipelines its nodes run comes through {@link Facts}, so each rule is tested
 * on its own (CarryOnTest).
 *
 * <ul>
 *   <li>A node that succeeded, every node before it kept, is kept: not run again, its values read by
 *       later nodes as before.</li>
 *   <li>A node that failed and cannot be run again is kept as it failed when a connection handled the
 *       failure; when nothing did, the run is not tried again (it would fail the same way).</li>
 *   <li>Every other node runs again, as its connections fall this time: parallel branches carry on
 *       each from its own state.</li>
 *   <li>A node that ran before and is not kept must be safe to run again; one that is not makes the
 *       run not tried again, naming it.</li>
 * </ul>
 */
final class CarryOn {

    private CarryOn() {
    }

    /** What the plan needs to know about the pipelines a node runs. */
    interface Facts {
        /**
         * Why running node {@code s}'s pipeline again could write twice ("orders, whose write-x appends"),
         * or null when every write it would make replaces or upserts.
         */
        String unsafe(FlowDefinition.Node s);

        /**
         * Why the node's last pipeline runs failed in a way a second try cannot change (a quality gate
         * stopped one, one was refused), or null.
         */
        String unchangeable(FlowDefinition.Node s);
    }

    /**
     * The plan for the next try: {@code keep} the node rows that stand, by node; {@code again} the nodes
     * that ran before and run again; {@code blocker} why the run must not be tried again, or null.
     */
    record Plan(Map<String, FlowStore.NodeRun> keep, Set<String> again, String blocker) {
    }

    /**
     * Plans the next try from the rows of the try before.
     *
     * @param byHand a person's Run again from the failure: a node's own retries and a failure a second
     *               try could not change by itself (a person may have fixed its cause) do not stop it;
     *               writes that are not safe to repeat always do
     */
    static Plan plan(FlowDefinition def, List<FlowStore.NodeRun> rows, Facts facts, boolean byHand) {
        Map<String, FlowStore.NodeRun> byNode = new HashMap<>();
        rows.forEach(r -> byNode.put(r.nodeId(), r));
        Map<String, Set<String>> before = new HashMap<>();
        Set<String> handled = new HashSet<>();
        for (FlowDefinition.Connection c : def.connections()) {
            before.computeIfAbsent(c.to(), k -> new HashSet<>()).add(c.from());
            if (c.kind() == FlowEngine.Kind.FAILURE || c.kind() == FlowEngine.Kind.ALWAYS) {
                handled.add(c.from());
            }
        }
        Map<String, FlowStore.NodeRun> keep = new LinkedHashMap<>();
        Map<String, String> why = new HashMap<>();
        boolean changed = true;
        while (changed) {
            changed = false;
            for (FlowDefinition.Node s : def.nodes()) {
                FlowStore.NodeRun r = byNode.get(s.id());
                if (keep.containsKey(s.id()) || r == null
                    || !keep.keySet().containsAll(before.getOrDefault(s.id(), Set.of()))) {
                    continue;
                }
                if ("succeeded".equals(r.status()) && !FlowDefinition.STOP.equals(s.type())) {
                    keep.put(s.id(), r);
                    changed = true;
                } else if ("failed".equals(r.status()) && handled.contains(s.id())) {
                    String w = why.computeIfAbsent(s.id(), k -> notAgain(s, r, facts, byHand));
                    if (w != null) {
                        keep.put(s.id(), r); // it would fail the same way; its failure was dealt with before
                        changed = true;
                    }
                }
            }
        }
        Set<String> again = new HashSet<>();
        String blocker = null;
        for (FlowDefinition.Node s : def.nodes()) {
            FlowStore.NodeRun r = byNode.get(s.id());
            if (r == null || keep.containsKey(s.id()) || !ran(r)) {
                continue;
            }
            if (FlowDefinition.STOP.equals(s.type())) {
                blocker = blocker != null ? blocker : "the stop node " + s.id() + " ended it";
                continue;
            }
            again.add(s.id());
            String w = why.computeIfAbsent(s.id(), k -> notAgain(s, r, facts, byHand));
            if (w != null && blocker == null) {
                blocker = "node " + s.id() + " " + w;
            }
        }
        return new Plan(keep, again, blocker);
    }

    /** Whether a node's row says it ran (or began to): not skipped, not only waiting to start. */
    private static boolean ran(FlowStore.NodeRun r) {
        return !"skipped".equals(r.status());
    }

    /** Why node {@code s}, as its row {@code r} left it, must not run again; null when it may. */
    static String notAgain(FlowDefinition.Node s, FlowStore.NodeRun r, Facts facts, boolean byHand) {
        String type = s.type();
        if (FlowDefinition.SQL.equals(type)) {
            if (r.reported() != null && r.reported().contains("\"" + FlowService.IN_DOUBT + "\":true")) {
                return "may have committed: its commit failed, or its worker stopped while it ran";
            }
            if ("succeeded".equals(r.status())) {
                return "committed its changes, and would make them again";
            }
            return null; // its statements run in one transaction, rolled back when it failed or was stopped
        }
        if (!FlowService.runsPipeline(s)) {
            return null; // waits, checks and notices change nothing that a second try would change twice
        }
        if (!byHand && s.retries() > 0 && !"succeeded".equals(r.status())) {
            return "has retries of its own, and used them (a node's retries take the place of the run's)";
        }
        String unsafe = facts.unsafe(s);
        if (unsafe != null) {
            return "runs " + s.pipeline() + ", and trying again could write twice: " + unsafe;
        }
        if (!byHand && !"succeeded".equals(r.status())) {
            String fixed = facts.unchangeable(s);
            if (fixed != null) {
                return fixed;
            }
        }
        return null;
    }

    /** The node ids of {@code plan} that run again, in the flow's order, for messages. */
    static List<String> againInOrder(FlowDefinition def, Plan plan) {
        List<String> out = new ArrayList<>();
        def.nodes().forEach(n -> {
            if (plan.again().contains(n.id())) {
                out.add(n.id());
            }
        });
        return out;
    }
}

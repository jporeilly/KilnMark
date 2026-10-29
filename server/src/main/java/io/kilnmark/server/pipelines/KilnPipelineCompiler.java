package io.kilnmark.server.pipelines;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.apache.arrow.vector.types.pojo.Schema;

import io.kilnmark.kiln.NodeException;
import io.kilnmark.kiln.NodeFactories;
import io.kilnmark.kiln.NodeFactory;
import io.kilnmark.kiln.graph.Edge;
import io.kilnmark.kiln.graph.PipelineGraph;
import io.kilnmark.kiln.yaml.Compiled;
import io.kilnmark.kiln.yaml.PipelineCompiler;
import io.kilnmark.kiln.yaml.PipelineDefinition;

/**
 * {@link PipelineCompilerPort} over kiln-core's {@link PipelineCompiler}: maps its problems, notes,
 * nodes, columns and edges to the server's views. Checking never creates a node or opens a
 * connection; {@link #compile} creates the nodes of one run through {@link Compiled#graph()}.
 */
final class KilnPipelineCompiler implements PipelineCompilerPort {

    @Override
    public Compilation check(String yaml, NodeFactories factories) {
        return map(new PipelineCompiler(factories).compile(yaml), factories, false);
    }

    @Override
    public Compilation compile(String yaml, NodeFactories factories) {
        return map(new PipelineCompiler(factories).compile(yaml), factories, true);
    }

    /**
     * Checks a pipeline built in code (a preview's cut-down pipeline) and, with {@code create},
     * creates its nodes as {@link #compile} does: called inside {@link RunContext#run} then.
     */
    static Compilation compile(PipelineDefinition definition, NodeFactories factories, boolean create) {
        return map(new PipelineCompiler(factories).compile(definition), factories, create);
    }

    private static Compilation map(Compiled c, NodeFactories factories, boolean create) {
        List<PipelineCompilerPort.Problem> problems = new ArrayList<>(c.problems().stream()
            .map(KilnPipelineCompiler::problem).toList());
        List<PipelineCompilerPort.Problem> notes = new ArrayList<>(c.notes().stream().map(KilnPipelineCompiler::problem)
            .toList());
        PipelineDefinition d = c.definition();
        int gateProblems = gates(c, factories, problems, notes, create ? RunContext.current().orElse(null) : null);
        List<NodeOutline> nodes = new ArrayList<>();
        Map<String, Map<String, Object>> configs = new LinkedHashMap<>();
        if (d != null) {
            for (PipelineDefinition.NodeDefinition s : d.nodes()) {
                configs.putIfAbsent(s.id(), s.config());
                NodeFactory f = s.type() == null ? null : factories.get(s.type()).orElse(null);
                List<String> inputs = f == null ? List.of() : ports(f.inputPorts());
                List<String> outputs = List.of();
                if (f != null) {
                    try {
                        outputs = ports(f.outputPorts(s.config()));
                    } catch (RuntimeException e) {
                        outputs = ports(f.outputPorts());
                    }
                }
                Map<String, Schema> known = c.schemas().getOrDefault(s.id(), Map.of());
                Map<String, List<Column>> columns = new LinkedHashMap<>();
                for (String port : outputs) {
                    Schema schema = known.get(port);
                    columns.put(port, schema == null ? null : schema.getFields().stream()
                        .map(field -> new Column(field.getName(), ArrowKinds.kind(field))).toList());
                }
                nodes.add(new NodeOutline(s.id(), s.type(), f == null ? null : f.kind().name().toLowerCase(Locale.ROOT),
                    s.position() == null ? 0 : s.position().line(), inputs, outputs, columns));
            }
        }
        List<EdgeOutline> edges = new ArrayList<>();
        for (Edge e : c.edges()) {
            edges.add(new EdgeOutline(e.from().node(), e.from().port(), e.to().node(), e.to().port()));
        }
        String name = d == null ? null : d.name();
        String description = d == null ? null : d.description();
        if (!create || !c.ok() || gateProblems > 0) {
            return new Compilation(name, description, problems, notes, nodes, edges, configs, null);
        }
        RunContext.current().ifPresent(run -> noteWriteColumns(c, run));
        try {
            PipelineGraph graph = c.graph();
            return new Compilation(name, description, problems, notes, nodes, edges, configs, graph);
        } catch (NodeException | RuntimeException e) {
            String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            return new Compilation(name, description, List.of(new PipelineCompilerPort.Problem(0, 0, null, message)),
                notes, nodes, edges, configs, null);
        }
    }

    /**
     * What each gate node checks, which needs the whole graph: the read node upstream whose asset's
     * rules it takes ({@code asset_rules}, {@code rules_from}); a note when it would check nothing.
     * With {@code run}, tells the run which read each gate takes its rules from. Returns the number
     * of problems added.
     */
    static int gates(Compiled c, NodeFactories factories, List<PipelineCompilerPort.Problem> problems,
                     List<PipelineCompilerPort.Problem> notes, RunContext run) {
        PipelineDefinition d = c.definition();
        if (d == null) {
            return 0;
        }
        Map<String, String> types = new LinkedHashMap<>();
        Map<String, PipelineDefinition.NodeDefinition> byId = new LinkedHashMap<>();
        for (PipelineDefinition.NodeDefinition s : d.nodes()) {
            types.putIfAbsent(s.id(), s.type());
            byId.putIfAbsent(s.id(), s);
        }
        GateNodeFactory gate = factories.get(GateNodeFactory.TYPE).filter(GateNodeFactory.class::isInstance)
            .map(GateNodeFactory.class::cast).orElse(null);
        int added = 0;
        if (gate != null) {
            Map<String, Map<String, Object>> configs = new LinkedHashMap<>();
            byId.forEach((id, s) -> configs.put(id, c.configs().getOrDefault(id, s.config())));
            for (GateNodeFactory.Finding f : GateNodeFactory.graphChecks(types, configs, c.edges())) {
                PipelineDefinition.NodeDefinition s = byId.get(f.node());
                io.kilnmark.kiln.yaml.Position at = f.key() == null ? s.position() : s.at(f.key());
                PipelineCompilerPort.Problem p = new PipelineCompilerPort.Problem(at.line(), at.column(), s.id(),
                    "Node " + s.id() + ": " + f.message());
                if (f.note()) {
                    notes.add(p);
                } else {
                    problems.add(p);
                    added++;
                }
            }
        }
        for (PipelineDefinition.NodeDefinition s : d.nodes()) {
            if (!GateNodeFactory.TYPE.equals(s.type()) || gate == null) {
                continue;
            }
            Map<String, Object> config = c.configs().getOrDefault(s.id(), s.config());
            GateNodeFactory.Upstream up = GateNodeFactory.upstream(s.id(), config, c.edges(), types);
            if (up.problem() != null) {
                io.kilnmark.kiln.yaml.Position at = up.key() == null ? s.position() : s.at(up.key());
                problems.add(new PipelineCompilerPort.Problem(at.line(), at.column(), s.id(), "Node " + s.id() + ": "
                    + up.problem()));
                added++;
                continue;
            }
            boolean inline = config.get("rules") instanceof List<?> l && !l.isEmpty();
            if (up.read() == null) {
                if (GateNodeFactory.assetRules(config) && !inline) {
                    notes.add(new PipelineCompilerPort.Problem(s.position().line(), s.position().column(), s.id(),
                        "Node " + s.id() + ": no read node is upstream, so there is no asset's rules to check; write "
                            + "rules on the gate"));
                }
                continue;
            }
            Map<String, Object> readConfig = c.configs().getOrDefault(up.read(), byId.get(up.read()).config());
            RunContext.ReadRef ref = GateNodeFactory.readOf(readConfig);
            if (run != null) {
                run.gateRead(s.id(), ref);
            } else if (!inline && !gate.hasRules(PipelineSources.urn(ref.source(), ref.asset()))) {
                notes.add(new PipelineCompilerPort.Problem(s.position().line(), s.position().column(), s.id(),
                    "Node " + s.id() + ": " + (ref.asset() == null ? "the asset read" : ref.asset()) + " has no quality "
                        + "rules yet, so the gate has nothing to check. Add rules to it in Quality, or write rules on "
                        + "the gate"));
            }
        }
        return added;
    }

    private static PipelineCompilerPort.Problem problem(io.kilnmark.kiln.yaml.Problem p) {
        return new PipelineCompilerPort.Problem(p.line(), p.column(), p.nodeId(), p.message());
    }

    private static List<String> ports(List<String> declared) {
        return declared.stream().map(d -> d.endsWith("*") ? d.substring(0, d.length() - 1) : d).toList();
    }

    /**
     * Tells the run which columns arrive at each write (as the check worked them out), for the
     * written asset's profile settings (which columns keep a distinct-value sketch).
     */
    private static void noteWriteColumns(Compiled c, RunContext run) {
        for (PipelineDefinition.NodeDefinition s : c.definition().nodes()) {
            if (!WriteNodeFactory.TYPE.equals(s.type())) {
                continue;
            }
            for (Edge e : c.edges()) {
                if (!e.to().node().equals(s.id())) {
                    continue;
                }
                Schema schema = c.schemas().getOrDefault(e.from().node(), Map.of()).get(e.from().port());
                if (schema != null) {
                    run.writeColumns(PipelineSources.text(s.config(), "source"), WriteNodeFactory.target(s.config()),
                        schema.getFields().stream().map(org.apache.arrow.vector.types.pojo.Field::getName).toList());
                }
            }
        }
    }
}

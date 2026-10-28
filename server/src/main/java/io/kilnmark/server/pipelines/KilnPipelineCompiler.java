package io.kilnmark.server.pipelines;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.apache.arrow.vector.types.pojo.Schema;

import io.kilnmark.kiln.StepException;
import io.kilnmark.kiln.StepFactories;
import io.kilnmark.kiln.StepFactory;
import io.kilnmark.kiln.graph.Edge;
import io.kilnmark.kiln.graph.PipelineGraph;
import io.kilnmark.kiln.yaml.Compiled;
import io.kilnmark.kiln.yaml.PipelineCompiler;
import io.kilnmark.kiln.yaml.PipelineDefinition;

/**
 * {@link PipelineCompilerPort} over kiln-core's {@link PipelineCompiler}: maps its problems, notes,
 * steps, columns and edges to the server's views. Checking never creates a step or opens a
 * connection; {@link #compile} creates the steps of one run through {@link Compiled#graph()}.
 */
final class KilnPipelineCompiler implements PipelineCompilerPort {

    @Override
    public Compilation check(String yaml, StepFactories factories) {
        return map(new PipelineCompiler(factories).compile(yaml), factories, false);
    }

    @Override
    public Compilation compile(String yaml, StepFactories factories) {
        return map(new PipelineCompiler(factories).compile(yaml), factories, true);
    }

    private static Compilation map(Compiled c, StepFactories factories, boolean create) {
        List<PipelineCompilerPort.Problem> problems = new ArrayList<>(c.problems().stream()
            .map(KilnPipelineCompiler::problem).toList());
        List<PipelineCompilerPort.Problem> notes = new ArrayList<>(c.notes().stream().map(KilnPipelineCompiler::problem)
            .toList());
        PipelineDefinition d = c.definition();
        int gateProblems = gates(c, factories, problems, notes, create ? RunContext.current().orElse(null) : null);
        List<StepOutline> steps = new ArrayList<>();
        Map<String, Map<String, Object>> configs = new LinkedHashMap<>();
        if (d != null) {
            for (PipelineDefinition.StepDefinition s : d.steps()) {
                configs.putIfAbsent(s.id(), s.config());
                StepFactory f = s.type() == null ? null : factories.get(s.type()).orElse(null);
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
                steps.add(new StepOutline(s.id(), s.type(), f == null ? null : f.kind().name().toLowerCase(Locale.ROOT),
                    s.position() == null ? 0 : s.position().line(), inputs, outputs, columns));
            }
        }
        List<EdgeOutline> edges = new ArrayList<>();
        for (Edge e : c.edges()) {
            edges.add(new EdgeOutline(e.from().step(), e.from().port(), e.to().step(), e.to().port()));
        }
        String name = d == null ? null : d.name();
        String description = d == null ? null : d.description();
        if (!create || !c.ok() || gateProblems > 0) {
            return new Compilation(name, description, problems, notes, steps, edges, configs, null);
        }
        RunContext.current().ifPresent(run -> noteWriteColumns(c, run));
        try {
            PipelineGraph graph = c.graph();
            return new Compilation(name, description, problems, notes, steps, edges, configs, graph);
        } catch (StepException | RuntimeException e) {
            String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            return new Compilation(name, description, List.of(new PipelineCompilerPort.Problem(0, 0, null, message)),
                notes, steps, edges, configs, null);
        }
    }

    /**
     * What each gate step checks, which needs the whole graph: the read step upstream whose asset's
     * rules it takes ({@code asset_rules}, {@code rules_from}); a note when it would check nothing.
     * With {@code run}, tells the run which read each gate takes its rules from. Returns the number
     * of problems added.
     */
    static int gates(Compiled c, StepFactories factories, List<PipelineCompilerPort.Problem> problems,
                     List<PipelineCompilerPort.Problem> notes, RunContext run) {
        PipelineDefinition d = c.definition();
        if (d == null) {
            return 0;
        }
        Map<String, String> types = new LinkedHashMap<>();
        Map<String, PipelineDefinition.StepDefinition> byId = new LinkedHashMap<>();
        for (PipelineDefinition.StepDefinition s : d.steps()) {
            types.putIfAbsent(s.id(), s.type());
            byId.putIfAbsent(s.id(), s);
        }
        GateStepFactory gate = factories.get(GateStepFactory.TYPE).filter(GateStepFactory.class::isInstance)
            .map(GateStepFactory.class::cast).orElse(null);
        int added = 0;
        for (PipelineDefinition.StepDefinition s : d.steps()) {
            if (!GateStepFactory.TYPE.equals(s.type()) || gate == null) {
                continue;
            }
            Map<String, Object> config = c.configs().getOrDefault(s.id(), s.config());
            GateStepFactory.Upstream up = GateStepFactory.upstream(s.id(), config, c.edges(), types);
            if (up.problem() != null) {
                io.kilnmark.kiln.yaml.Position at = s.at(up.key());
                problems.add(new PipelineCompilerPort.Problem(at.line(), at.column(), s.id(), "Step " + s.id() + ": "
                    + up.problem()));
                added++;
                continue;
            }
            boolean inline = config.get("rules") instanceof List<?> l && !l.isEmpty();
            if (up.read() == null) {
                if (GateStepFactory.assetRules(config) && !inline) {
                    notes.add(new PipelineCompilerPort.Problem(s.position().line(), s.position().column(), s.id(),
                        "Step " + s.id() + ": no read step is upstream, so there is no asset's rules to check; write "
                            + "rules on the gate"));
                }
                continue;
            }
            Map<String, Object> readConfig = c.configs().getOrDefault(up.read(), byId.get(up.read()).config());
            RunContext.ReadRef ref = GateStepFactory.readOf(readConfig);
            if (run != null) {
                run.gateRead(s.id(), ref);
            } else if (!inline && !gate.hasRules(PipelineSources.urn(ref.source(), ref.asset()))) {
                notes.add(new PipelineCompilerPort.Problem(s.position().line(), s.position().column(), s.id(),
                    "Step " + s.id() + ": " + (ref.asset() == null ? "the asset read" : ref.asset()) + " has no quality "
                        + "rules yet, so the gate has nothing to check. Add rules to it in Quality, or write rules on "
                        + "the gate"));
            }
        }
        return added;
    }

    private static PipelineCompilerPort.Problem problem(io.kilnmark.kiln.yaml.Problem p) {
        return new PipelineCompilerPort.Problem(p.line(), p.column(), p.stepId(), p.message());
    }

    private static List<String> ports(List<String> declared) {
        return declared.stream().map(d -> d.endsWith("*") ? d.substring(0, d.length() - 1) : d).toList();
    }

    /**
     * Tells the run which columns arrive at each write (as the check worked them out), for the
     * written asset's profile settings (which columns keep a distinct-value sketch).
     */
    private static void noteWriteColumns(Compiled c, RunContext run) {
        for (PipelineDefinition.StepDefinition s : c.definition().steps()) {
            if (!WriteStepFactory.TYPE.equals(s.type())) {
                continue;
            }
            for (Edge e : c.edges()) {
                if (!e.to().step().equals(s.id())) {
                    continue;
                }
                Schema schema = c.schemas().getOrDefault(e.from().step(), Map.of()).get(e.from().port());
                if (schema != null) {
                    run.writeColumns(PipelineSources.text(s.config(), "source"), WriteStepFactory.target(s.config()),
                        schema.getFields().stream().map(org.apache.arrow.vector.types.pojo.Field::getName).toList());
                }
            }
        }
    }
}

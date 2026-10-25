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
        List<PipelineCompilerPort.Problem> problems = c.problems().stream().map(KilnPipelineCompiler::problem).toList();
        List<PipelineCompilerPort.Problem> notes = c.notes().stream().map(KilnPipelineCompiler::problem).toList();
        PipelineDefinition d = c.definition();
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
        if (!create || !c.ok()) {
            return new Compilation(name, description, problems, notes, steps, edges, configs, null);
        }
        try {
            PipelineGraph graph = c.graph();
            return new Compilation(name, description, problems, notes, steps, edges, configs, graph);
        } catch (StepException | RuntimeException e) {
            String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            return new Compilation(name, description, List.of(new PipelineCompilerPort.Problem(0, 0, null, message)),
                notes, steps, edges, configs, null);
        }
    }

    private static PipelineCompilerPort.Problem problem(io.kilnmark.kiln.yaml.Problem p) {
        return new PipelineCompilerPort.Problem(p.line(), p.column(), p.stepId(), p.message());
    }

    private static List<String> ports(List<String> declared) {
        return declared.stream().map(d -> d.endsWith("*") ? d.substring(0, d.length() - 1) : d).toList();
    }
}

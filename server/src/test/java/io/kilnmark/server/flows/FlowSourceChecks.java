package io.kilnmark.server.flows;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import io.kilnmark.kiln.yaml.FlowDefinition;
import io.kilnmark.kiln.yaml.PipelineDefinition;
import io.kilnmark.kiln.yaml.Problem;
import io.kilnmark.server.pipelines.PipelineSources;

/**
 * The checks saving a flow makes ({@code FlowService.validate}) of the sources its sql, check-source
 * and wait-for nodes name, and of the pipelines its nodes run, for tests outside this package: the
 * components reference checks its flow examples, and what each connector can do in a flow, with them.
 */
public final class FlowSourceChecks {

    private FlowSourceChecks() {
    }

    /** What saving would say about the sources {@code flow}'s nodes name; empty when every one may be used. */
    public static List<String> problems(FlowDefinition flow, PipelineSources sources) {
        List<Problem> problems = new ArrayList<>();
        for (FlowDefinition.Node n : flow.nodes()) {
            FlowService.sourceProblems(n, sources, problems);
        }
        return problems.stream().map(Problem::message).toList();
    }

    /**
     * What saving would say about the pipelines {@code flow}'s nodes run, with {@code pipelines} as the
     * saved ones (by name, each at its latest version) and no server variables.
     */
    public static List<String> pipelineProblems(FlowDefinition flow, Map<String, PipelineDefinition> pipelines) {
        List<Problem> problems = new ArrayList<>();
        List<String> out = new ArrayList<>();
        for (FlowDefinition.Node n : flow.nodes()) {
            if (!FlowService.runsPipeline(n) || n.pipeline() == null) {
                continue;
            }
            PipelineDefinition p = pipelines.get(n.pipeline());
            if (p == null) {
                out.add("Node " + n.id() + " runs the pipeline '" + n.pipeline() + "', which does not exist");
            } else {
                FlowService.pipelineProblems(flow, n, p, Map.of(), problems);
            }
        }
        problems.forEach(p -> out.add(p.message()));
        return out;
    }
}

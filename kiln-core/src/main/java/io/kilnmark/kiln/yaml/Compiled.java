package io.kilnmark.kiln.yaml;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.apache.arrow.vector.types.pojo.Schema;

import io.kilnmark.kiln.Step;
import io.kilnmark.kiln.StepException;
import io.kilnmark.kiln.StepFactory;
import io.kilnmark.kiln.graph.Edge;
import io.kilnmark.kiln.graph.GraphStep;
import io.kilnmark.kiln.graph.PipelineGraph;

/**
 * A pipeline checked by {@link PipelineCompiler}: its problems, notes and the columns leaving each
 * step, worked out without creating a step or opening a connection. {@link #graph()} creates the
 * steps, for one run.
 */
public final class Compiled {

    private final PipelineDefinition definition;
    private final List<Problem> problems;
    private final List<Problem> notes;
    private final Map<String, Map<String, Schema>> schemas;
    private final Map<String, StepFactory> factories;
    private final List<Edge> edges;
    private final Map<String, Map<String, Object>> configs;
    private final Map<String, Map<String, Schema>> inputSchemas;

    Compiled(PipelineDefinition definition, List<Problem> problems, List<Problem> notes,
             Map<String, Map<String, Schema>> schemas, Map<String, StepFactory> factories, List<Edge> edges,
             Map<String, Map<String, Object>> configs, Map<String, Map<String, Schema>> inputSchemas) {
        this.definition = definition;
        this.problems = problems.stream().sorted(Problem.BY_POSITION).toList();
        this.notes = notes.stream().sorted(Problem.BY_POSITION).toList();
        Map<String, Map<String, Schema>> s = new LinkedHashMap<>();
        schemas.forEach((step, ports) -> s.put(step, Collections.unmodifiableMap(new LinkedHashMap<>(ports))));
        this.schemas = Collections.unmodifiableMap(s);
        this.factories = Map.copyOf(factories);
        this.edges = List.copyOf(edges);
        this.configs = Collections.unmodifiableMap(new LinkedHashMap<>(configs));
        Map<String, Map<String, Schema>> in = new LinkedHashMap<>();
        inputSchemas.forEach((step, ports) -> in.put(step, Collections.unmodifiableMap(new LinkedHashMap<>(ports))));
        this.inputSchemas = Collections.unmodifiableMap(in);
    }

    /**
     * Each step's settings as its step receives them: a setting that is text holds the text as
     * written ({@code 007}, {@code 2024.10}), a number is a whole number or a BigDecimal.
     */
    public Map<String, Map<String, Object>> configs() {
        return configs;
    }

    /**
     * The columns arriving on each step's input ports where known, keyed as {@code outputSchema}
     * receives them ({@code in}, {@code main}, {@code in[0]}); the runner passes them to the step as
     * {@link io.kilnmark.kiln.StepContext#inputSchema(String)}.
     */
    public Map<String, Map<String, Schema>> inputSchemas() {
        return inputSchemas;
    }

    /** The pipeline as written, or null when its YAML could not be read as a pipeline. */
    public PipelineDefinition definition() {
        return definition;
    }

    /** What stops the pipeline running, by position; empty when it can run. */
    public List<Problem> problems() {
        return problems;
    }

    /** Things worth knowing that do not stop a run, such as steps whose columns are not known until it runs. */
    public List<Problem> notes() {
        return notes;
    }

    /**
     * The columns leaving each step, by step id and then output port. A port is missing when its
     * columns cannot be known before the run, or when the step has a problem.
     */
    public Map<String, Map<String, Schema>> schemas() {
        return schemas;
    }

    /** The edges, in the order the steps name their inputs. */
    public List<Edge> edges() {
        return edges;
    }

    /** Whether the pipeline can run. */
    public boolean ok() {
        return problems.isEmpty() && definition != null;
    }

    /**
     * A graph with new steps, for one run: each call creates them afresh. This is where steps are
     * made, and may open connections; checking never does.
     *
     * @throws IllegalStateException if the pipeline has problems
     * @throws StepException         if a step cannot be created; steps already created are closed
     */
    public PipelineGraph graph() throws StepException {
        if (!ok()) {
            throw new IllegalStateException(problems.isEmpty() ? "The pipeline could not be read"
                : "The pipeline cannot run: " + problems.getFirst());
        }
        List<GraphStep> steps = new ArrayList<>();
        try {
            for (PipelineDefinition.StepDefinition s : definition.steps()) {
                Map<String, Object> config = configs.getOrDefault(s.id(), s.config());
                Step step;
                try {
                    step = factories.get(s.id()).create(config);
                } catch (StepException | RuntimeException e) {
                    throw new StepException("Step " + s.id() + " (" + s.type() + ") could not be created: "
                        + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()), e);
                }
                if (step == null) {
                    throw new StepException("Step " + s.id() + " (" + s.type() + ") could not be created");
                }
                steps.add(new GraphStep(s.id(), step, config, inputSchemas.get(s.id())));
            }
        } catch (Throwable t) { // StepException, or an Error from a factory: close what was made
            for (GraphStep g : steps) {
                try {
                    g.step().close();
                } catch (RuntimeException ignored) {
                    // the creation failure is what is reported
                }
            }
            throw t;
        }
        return new PipelineGraph(definition.name(), steps, edges);
    }
}

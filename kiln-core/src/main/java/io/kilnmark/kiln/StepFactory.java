package io.kilnmark.kiln;

import java.util.List;
import java.util.Map;

import org.apache.arrow.vector.types.pojo.Schema;

/**
 * A type of step that pipelines can use: what it is called, its ports, its settings and how to
 * make one (docs/design/pipelines.md, ADR 0022). Found through {@link java.util.ServiceLoader} or
 * registered with {@link StepFactories}; the step catalogue API and the canvas palette list them.
 *
 * <p>An input port whose name ends in {@code *} (for example {@code in*}) takes any number of
 * edges, as union's does.
 */
public interface StepFactory {

    /** The {@code type} used in pipeline YAML, for example {@code filter}. Lower case, dashes allowed. */
    String type();

    StepKind kind();

    /**
     * The palette folder this step type is listed in, one of {@link StepCategories}. By default it
     * follows the kind: sources are Read, sinks Write, gates Quality, transforms Other; a transform
     * should name its own.
     */
    default String category() {
        return StepCategories.defaultFor(kind());
    }

    /** The name people read, for example "Filter rows". */
    String label();

    /** One or two sentences on what the step does. */
    String help();

    /** Input ports, for example {@code ["in"]}, {@code ["main", "lookup"]}, or none for a source. */
    List<String> inputPorts();

    /**
     * Output ports, for example {@code ["out"]} or {@code ["out", "rejected"]}, or none for a sink.
     * A step whose ports depend on its settings (route) lists the fixed ones and reports the rest in
     * {@link #outputPorts(Map)}.
     */
    List<String> outputPorts();

    /** Output ports for these settings. */
    default List<String> outputPorts(Map<String, Object> config) {
        return outputPorts();
    }

    /** The settings the step takes, in the order the settings panel shows them. */
    List<SettingSpec> settings();

    /**
     * Problems with these settings in plain words, each starting with the setting's key; empty
     * when they are valid. Called when a pipeline is validated, before anything runs.
     */
    default List<String> check(Map<String, Object> config) {
        return List.of();
    }

    /** A new step for one run, with these settings (already checked). */
    Step create(Map<String, Object> config) throws StepException;

    /**
     * The columns leaving {@code port}, given the columns arriving on each input port, without
     * reading data; null when the step cannot tell before it runs. Lets validation and the canvas
     * show each step's columns, and catch an unknown column before a run.
     *
     * @throws StepException naming the problem, for example an expression using a column the input
     *                       does not have
     */
    default Schema outputSchema(String port, Map<String, Schema> inputs, Map<String, Object> config)
        throws StepException {
        return null;
    }
}

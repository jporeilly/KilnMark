package io.kilnmark.kiln;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import org.apache.arrow.memory.BufferAllocator;

/** What the runner gives a step: its name, settings, memory, row sets and the metadata side-channel. */
public interface StepContext {

    /** The step's name within its pipeline, unique per pipeline. */
    String stepName();

    /** Settings from the pipeline definition. */
    Map<String, String> settings();

    /** Where the step reports what it learns about the data it touches. */
    MetadataEmitter metadata();

    /** This step's memory: batches it creates, or takes from its input, are accounted here. */
    BufferAllocator allocator();

    /** Batches from the step before. */
    RowSet input();

    /** Batches for the step after. */
    RowSet output();

    /** Whether the step has a step before it. A source has none. */
    boolean hasInput();

    /** Whether the step has a step after it. A sink has none. */
    boolean hasOutput();

    /** A required setting. */
    default String setting(String key) throws StepException {
        String value = settings().get(key);
        if (value == null || value.isBlank()) {
            throw new StepException("Step " + stepName() + " needs the setting '" + key + "'");
        }
        return value;
    }

    /** An optional setting. */
    default String setting(String key, String fallback) {
        String value = settings().get(key);
        return value == null || value.isBlank() ? fallback : value;
    }

    // ---- Pipelines as graphs (docs/design/pipelines.md, ADR 0022) ----

    /**
     * The step's settings as written in the pipeline YAML: maps, lists, strings, numbers and
     * booleans. Steps built from flat settings see each value as a string.
     */
    default Map<String, Object> config() {
        return new LinkedHashMap<>(settings());
    }

    /** The names of the input ports that are connected. A step in a line has {@code in}, or none. */
    default Set<String> inputPorts() {
        return hasInput() ? Set.of("in") : Set.of();
    }

    /** The names of the output ports that are connected. A step in a line has {@code out}, or none. */
    default Set<String> outputPorts() {
        return hasOutput() ? Set.of("out") : Set.of();
    }

    /**
     * Batches arriving on an input port; {@code in} is {@link #input()}.
     *
     * @throws StepException if the port is not connected
     */
    default RowSet input(String port) throws StepException {
        if ("in".equals(port) && hasInput()) {
            return input();
        }
        throw new StepException("Step " + stepName() + " has no input connected to port '" + port + "'");
    }

    /**
     * Where batches leave through an output port; {@code out} is {@link #output()}. A port that
     * exists on the step but is not connected returns {@code null}: the step drops those rows.
     *
     * @throws StepException if the step has no such port at all
     */
    default RowSet output(String port) throws StepException {
        if ("out".equals(port)) {
            return hasOutput() ? output() : null;
        }
        throw new StepException("Step " + stepName() + " has no output port '" + port + "'");
    }
}

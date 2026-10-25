package io.kilnmark.kiln;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.arrow.memory.BufferAllocator;
import org.apache.arrow.vector.VectorSchemaRoot;
import org.apache.arrow.vector.types.pojo.Schema;

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

    /**
     * Every row set arriving on an input port, in the order the pipeline lists them: the way to
     * read a port that takes many inputs (a factory port named with a trailing {@code *}, such as
     * union's {@code in*}, is read here as {@code in}). Empty when nothing is connected to the port.
     * {@link RowSet#takeAny} reads several at once without one holding up the others.
     */
    default List<RowSet> inputs(String port) {
        if (inputPorts().contains(port)) {
            try {
                return List.of(input(port));
            } catch (StepException e) {
                return List.of();
            }
        }
        return List.of();
    }

    /**
     * The columns arriving on an input port, as worked out when the pipeline was checked, or
     * {@code null} when they could not be known before the run (or the step is not run from a
     * checked pipeline). Lets a step act on an input that sends no batch at all: an aggregate of no
     * rows still writes SQL's one row, a union adds the columns of an empty input, a lookup knows
     * the columns of an empty lookup side.
     */
    default Schema inputSchema(String port) {
        return null;
    }

    /**
     * The columns arriving on the {@code index}-th input (from 0, in the order the pipeline lists
     * them) of a port that takes many, such as union's {@code in}; {@code null} when not known.
     */
    default Schema inputSchema(String port, int index) {
        return null;
    }

    /**
     * Hands a batch to an output port, or closes it when nothing is connected to that port, so a
     * step with several outputs does not have to check each one. The step gives up the batch either
     * way.
     *
     * @throws StepException if the step has no such port, or the steps reading it have stopped
     */
    default void emit(String port, VectorSchemaRoot batch) throws StepException, InterruptedException {
        RowSet out;
        try {
            out = output(port);
        } catch (StepException | RuntimeException e) {
            batch.close();
            throw e;
        }
        if (out == null) {
            batch.close();
        } else {
            out.put(batch);
        }
    }
}

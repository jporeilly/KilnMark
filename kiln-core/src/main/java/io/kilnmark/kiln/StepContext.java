package io.kilnmark.kiln;

import java.util.Map;

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
}

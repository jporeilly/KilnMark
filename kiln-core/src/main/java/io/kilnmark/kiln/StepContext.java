package io.kilnmark.kiln;

import java.util.Map;

/** What the runner gives a step: its name, its settings and the metadata side-channel. */
public interface StepContext {

    /** The step's name within its pipeline, unique per pipeline. */
    String stepName();

    /** Settings from the pipeline YAML, already validated against the step's schema. */
    Map<String, String> settings();

    /** Where the step reports what it learns about the data it touches. */
    MetadataEmitter metadata();
}

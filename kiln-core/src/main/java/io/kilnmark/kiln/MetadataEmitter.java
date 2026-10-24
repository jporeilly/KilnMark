package io.kilnmark.kiln;

/**
 * Receives metadata events from running steps and forwards them to the graph. Steps run on their
 * own threads, so implementations must be thread-safe.
 */
@FunctionalInterface
public interface MetadataEmitter {

    void emit(MetadataEvent event);
}

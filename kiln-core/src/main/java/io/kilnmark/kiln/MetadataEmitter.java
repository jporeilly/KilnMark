package io.kilnmark.kiln;

/**
 * Receives metadata events from running nodes and forwards them to the graph. Nodes run on their
 * own threads, so implementations must be thread-safe.
 */
@FunctionalInterface
public interface MetadataEmitter {

    void emit(MetadataEvent event);
}

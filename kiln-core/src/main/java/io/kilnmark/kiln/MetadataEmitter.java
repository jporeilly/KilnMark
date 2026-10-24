package io.kilnmark.kiln;

/** Receives metadata events from running steps and forwards them to the graph. */
@FunctionalInterface
public interface MetadataEmitter {

    void emit(MetadataEvent event);
}

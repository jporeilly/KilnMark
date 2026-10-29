package io.kilnmark.kiln;

import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.Optional;
import java.util.ServiceLoader;
import java.util.TreeMap;

/**
 * The node types available to pipelines: those found through {@link ServiceLoader} on the class
 * path, plus any registered by the application (the server registers {@code read} and
 * {@code write}, which need its saved sources). A type registered twice is an error.
 */
public final class NodeFactories {

    private final Map<String, NodeFactory> byType = new TreeMap<>();

    /** Every factory on the class path. */
    public static NodeFactories discover() {
        NodeFactories f = new NodeFactories();
        for (NodeFactory factory : ServiceLoader.load(NodeFactory.class)) {
            f.register(factory);
        }
        return f;
    }

    public synchronized NodeFactories register(NodeFactory factory) {
        NodeFactory before = byType.putIfAbsent(factory.type(), factory);
        if (before != null && before != factory) {
            throw new IllegalStateException("Two node types are called " + factory.type() + ": "
                + before.getClass().getName() + " and " + factory.getClass().getName());
        }
        return this;
    }

    public synchronized Optional<NodeFactory> get(String type) {
        return Optional.ofNullable(byType.get(type));
    }

    /** All types, sorted by name. */
    public synchronized Collection<NodeFactory> all() {
        return Collections.unmodifiableCollection(new java.util.ArrayList<>(byType.values()));
    }
}

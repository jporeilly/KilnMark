package io.kilnmark.kiln.connect;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.ServiceLoader;
import java.util.TreeMap;

/** Every connector on the class path, by kind. */
public final class Connectors {

    private final Map<String, Connector> byKind;

    private Connectors(Map<String, Connector> byKind) {
        this.byKind = byKind;
    }

    /**
     * Loads the connectors listed in {@code META-INF/services} with no server-wide settings, so
     * those that need them stay at their safest defaults. Two with the same kind is an error.
     */
    public static Connectors load() {
        return load(ConnectorContext.EMPTY);
    }

    /** Loads the connectors listed in {@code META-INF/services}, each set up with {@code context}. */
    public static Connectors load(ConnectorContext context) {
        return of(ServiceLoader.load(Connector.class).stream().map(ServiceLoader.Provider::get)
            .map(c -> c.withContext(context)).toList());
    }

    public static Connectors of(Collection<? extends Connector> connectors) {
        Map<String, Connector> map = new TreeMap<>();
        for (Connector c : connectors) {
            Connector before = map.put(c.kind(), c);
            if (before != null) {
                throw new IllegalStateException("Two connectors claim the kind '" + c.kind() + "': "
                    + before.getClass().getName() + " and " + c.getClass().getName());
            }
        }
        return new Connectors(Map.copyOf(map));
    }

    public Optional<Connector> get(String kind) {
        return Optional.ofNullable(kind == null ? null : byKind.get(kind));
    }

    public Collection<Connector> all() {
        return new TreeMap<>(byKind).values();
    }
}

package io.kilnmark.server.pipelines;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

import io.kilnmark.kiln.NodeFactories;
import io.kilnmark.kiln.NodeFactory;

/** The server's node catalogue: kiln-nodes' transforms through ServiceLoader, and its own read and write. */
class NodeCatalogueTest {

    @Test
    void theTransformsAreOnTheServersClassPath() {
        Set<String> types = NodeFactories.discover().register(new ReadNodeFactory(new FakeSources()))
            .register(new WriteNodeFactory(new FakeSources())).all().stream().map(NodeFactory::type)
            .collect(Collectors.toSet());
        for (String t : List.of("filter", "compute", "select", "lookup", "aggregate", "union", "dedupe", "route",
            "limit", "read", "write")) {
            assertTrue(types.contains(t), t + " is missing from " + types);
        }
    }
}

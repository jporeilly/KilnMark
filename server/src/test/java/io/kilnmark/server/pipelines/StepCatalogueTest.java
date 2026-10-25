package io.kilnmark.server.pipelines;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

import io.kilnmark.kiln.StepFactories;
import io.kilnmark.kiln.StepFactory;

/** The server's step catalogue: kiln-steps' transforms through ServiceLoader, and its own read and write. */
class StepCatalogueTest {

    @Test
    void theTransformsAreOnTheServersClassPath() {
        Set<String> types = StepFactories.discover().register(new ReadStepFactory(new FakeSources()))
            .register(new WriteStepFactory(new FakeSources())).all().stream().map(StepFactory::type)
            .collect(Collectors.toSet());
        for (String t : List.of("filter", "compute", "select", "lookup", "aggregate", "union", "dedupe", "route",
            "limit", "read", "write")) {
            assertTrue(types.contains(t), t + " is missing from " + types);
        }
    }
}

package io.kilnmark.server.pipelines;

import java.util.List;
import java.util.Optional;

import io.kilnmark.graph.store.GraphStore;
import io.kilnmark.graph.store.Records;
import io.kilnmark.kiln.StepFactories;
import io.kilnmark.kiln.connect.Connector;
import io.kilnmark.kiln.connect.Connectors;
import io.kilnmark.kiln.graph.GraphRunner;
import io.kilnmark.server.KilnmarkConfig;
import io.kilnmark.server.SecretBox;
import io.kilnmark.server.sources.HostPolicy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;

/**
 * Where the pipeline engine meets the server: the step catalogue, the compiler and the graph
 * runner, each produced here and nowhere else.
 *
 * <ul>
 *   <li>{@link #compiler()}: kiln-core's {@code PipelineCompiler}, through {@link KilnPipelineCompiler}.</li>
 *   <li>{@link #runner}: kiln-core's {@code KilnGraphRunner}, inside {@code kilnmark.pipelines.memory-limit-mb}.</li>
 *   <li>{@link #steps}: every {@code StepFactory} on the class path (kiln-steps' transforms arrive
 *       through {@code ServiceLoader}) plus the server's {@code read} and {@code write}.</li>
 * </ul>
 */
@ApplicationScoped
public class PipelineCompilers {

    @Produces
    @ApplicationScoped
    PipelineCompilerPort compiler() {
        return new KilnPipelineCompiler();
    }

    @Produces
    @ApplicationScoped
    GraphRunner runner(KilnmarkConfig config) {
        KilnmarkConfig.Pipelines p = config.pipelines();
        return new io.kilnmark.kiln.graph.KilnGraphRunner(Math.max(16, p.memoryLimitMb()) << 20,
            Math.max(1, p.rowSetCapacity()));
    }

    @Produces
    @ApplicationScoped
    PipelineSources sources(GraphStore store, SecretBox secrets, Connectors connectors, KilnmarkConfig config) {
        HostPolicy hosts = new HostPolicy(config.sources().allowedHosts().orElse(List.of()));
        return new PipelineSources() {
            @Override
            public Optional<Records.Source> source(String name) {
                return store.source(name);
            }

            @Override
            public Optional<Connector> connector(String kind) {
                return connectors.get(kind);
            }

            @Override
            public String secret(Records.Source source) {
                return source.hasSecret() ? store.sourceSecret(source.name()).map(secrets::open).orElse(null) : null;
            }

            @Override
            public Optional<Records.AssetDetail> asset(String urn) {
                return store.asset(urn).filter(a -> a.removedAt() == null);
            }

            @Override
            public HostPolicy hosts() {
                return hosts;
            }
        };
    }

    // Singleton: StepFactories is a final class from kiln-core, which a client proxy cannot wrap.
    @Produces
    @jakarta.inject.Singleton
    StepFactories steps(PipelineSources sources) {
        return StepFactories.discover().register(new ReadStepFactory(sources)).register(new WriteStepFactory(sources));
    }
}

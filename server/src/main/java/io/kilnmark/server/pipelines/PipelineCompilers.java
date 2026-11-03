package io.kilnmark.server.pipelines;

import java.util.List;
import java.util.Optional;

import io.kilnmark.graph.store.GraphStore;
import io.kilnmark.graph.store.Records;
import io.kilnmark.kiln.NodeFactories;
import io.kilnmark.kiln.connect.Connector;
import io.kilnmark.kiln.connect.Connectors;
import io.kilnmark.kiln.graph.GraphRunner;
import io.kilnmark.server.KilnmarkConfig;
import io.kilnmark.server.SecretBox;
import io.kilnmark.server.sources.HostPolicy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;

/**
 * Where the pipeline engine meets the server: the node catalogue, the compiler and the graph
 * runner, each produced here and nowhere else.
 *
 * <ul>
 *   <li>{@link #compiler()}: kiln-core's {@code PipelineCompiler}, through {@link KilnPipelineCompiler}.</li>
 *   <li>{@link #runner}: kiln-core's {@code KilnGraphRunner}, inside {@code kilnmark.pipelines.memory-limit-mb}.</li>
 *   <li>{@link #nodes}: every {@code NodeFactory} on the class path (kiln-nodes' transforms arrive
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
    PipelineSources sources(GraphStore store, SecretBox secrets, Connectors connectors, KilnmarkConfig config,
                            io.kilnmark.server.quality.QualityService quality) {
        HostPolicy hosts = HostPolicy.of(config);
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

            @Override
            public List<io.kilnmark.server.quality.Rule> rules(String urn) {
                return quality.enabledRules(urn);
            }

            @Override
            public Long rowCount(String urn) {
                return quality.latestRowCount(urn);
            }

            @Override
            public List<Connector> connectors() {
                return List.copyOf(connectors.all());
            }

            @Override
            public java.util.Map<String, String> profileSettings(Records.Source source, Connector connector,
                                                                 String target, List<String> key, String urn) {
                return profileSettings(source, connector, target, key, urn, List.of());
            }

            @Override
            public java.util.Map<String, String> profileSettings(Records.Source source, Connector connector,
                                                                 String target, List<String> key, String urn,
                                                                 List<String> columns) {
                List<io.kilnmark.kiln.connect.AssetInfo.FieldInfo> fields = new java.util.ArrayList<>();
                for (int i = 0; i < columns.size(); i++) {
                    fields.add(new io.kilnmark.kiln.connect.AssetInfo.FieldInfo(columns.get(i), i + 1, null, null, true,
                        null));
                }
                io.kilnmark.kiln.connect.AssetInfo asset = new io.kilnmark.kiln.connect.AssetInfo(target, "table", null,
                    target, null, fields, key, false, true, java.util.Map.of());
                java.util.Map<String, String> s = new java.util.LinkedHashMap<>(quality.profileSettings(source,
                    connector, asset, urn));
                // Key-like columns keep their distinct-value sketch, by the scan's own rule.
                s.put(io.kilnmark.kiln.plugins.profile.ProfileNode.SKETCH_FIELDS,
                    io.kilnmark.server.relations.RelationshipService.sketchFields(asset));
                return s;
            }
        };
    }

    // Singleton: NodeFactories is a final class from kiln-core, which a client proxy cannot wrap.
    @Produces
    @jakarta.inject.Singleton
    NodeFactories nodes(PipelineSources sources) {
        return NodeFactories.discover().register(new ReadNodeFactory(sources)).register(new WriteNodeFactory(sources))
            .register(new GateNodeFactory(sources));
    }
}

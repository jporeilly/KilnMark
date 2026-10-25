package io.kilnmark.server.pipelines;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import io.kilnmark.graph.store.Records;
import io.kilnmark.kiln.connect.Connector;
import io.kilnmark.server.sources.HostPolicy;

/** Saved sources in memory, for testing the read and write steps without a database. */
final class FakeSources implements PipelineSources {

    final Map<String, Records.Source> sources = new HashMap<>();
    final Map<String, String> secrets = new HashMap<>();
    final Map<String, Records.AssetDetail> assets = new HashMap<>();
    final Map<String, Connector> connectors = new HashMap<>();
    HostPolicy hosts = new HostPolicy(List.of());
    /** The profiling settings every source gives its writes; null: keep-values from keep_samples, sample-size 10. */
    Map<String, String> profiling;

    FakeSources() {
        connectors.put(MemoryConnector.KIND, new MemoryConnector());
    }

    FakeSources add(String name, Map<String, String> settings, String secret) {
        return add(name, MemoryConnector.KIND, settings, secret);
    }

    FakeSources add(String name, String kind, Map<String, String> settings, String secret) {
        sources.put(name, new Records.Source(sources.size() + 1L, name, kind, settings, secret != null,
            Instant.now(), name));
        if (secret != null) {
            secrets.put(name, secret);
        }
        return this;
    }

    FakeSources asset(String urn, String... columns) {
        List<Records.FieldDetail> fields = new java.util.ArrayList<>();
        for (int i = 0; i < columns.length; i++) {
            String[] nameKind = columns[i].split(":");
            fields.add(new Records.FieldDetail(nameKind[0], i + 1, nameKind[1], nameKind[1], true, null, null, null));
        }
        assets.put(urn, new Records.AssetDetail(urn, "lab-db", "table", "crm", urn, null, List.of(), null, null,
            Instant.now(), Instant.now(), null, fields));
        return this;
    }

    @Override
    public Optional<Records.Source> source(String name) {
        return Optional.ofNullable(sources.get(name));
    }

    @Override
    public Optional<Connector> connector(String kind) {
        return Optional.ofNullable(connectors.get(kind));
    }

    @Override
    public String secret(Records.Source source) {
        return secrets.get(source.name());
    }

    @Override
    public Optional<Records.AssetDetail> asset(String urn) {
        return Optional.ofNullable(assets.get(urn));
    }

    @Override
    public HostPolicy hosts() {
        return hosts;
    }

    @Override
    public List<Connector> connectors() {
        return List.copyOf(connectors.values());
    }

    @Override
    public Map<String, String> profileSettings(Records.Source source, Connector connector, String target,
                                               List<String> key, String urn) {
        if (profiling != null) {
            return profiling;
        }
        return Map.of("keep-values", Boolean.toString(!"false".equals(source.settings().get("keep_samples"))),
            "sample-size", "10");
    }
}

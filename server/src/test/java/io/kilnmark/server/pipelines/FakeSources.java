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

    FakeSources() {
        connectors.put(MemoryConnector.KIND, new MemoryConnector());
    }

    FakeSources add(String name, Map<String, String> settings, String secret) {
        sources.put(name, new Records.Source(sources.size() + 1L, name, MemoryConnector.KIND, settings, secret != null,
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
}

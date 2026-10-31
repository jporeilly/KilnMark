package io.kilnmark.kiln.plugins.files;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import io.kilnmark.kiln.connect.ConnectorException;
import io.kilnmark.kiln.connect.SourceConfig;

/**
 * Kilnmark's sample data: small synthetic tables that ship inside Kilnmark, as CSV files in a
 * folder per group (crm, billing, shop, stock). The samples' setup pipeline copies them into a
 * database, where the samples read them; they can also be browsed and read directly, like any file
 * source. Read-only, with nothing to configure, and nothing outside Kilnmark's own jar is read.
 */
public final class SampleDataConnector extends FileSourceConnector {

    /** Where the files are, inside the jar (copied there from samples/data at build time). */
    static final String ROOT = "kilnmark/sample-data/";

    @Override
    public String kind() {
        return "sample-data";
    }

    @Override
    public String displayName() {
        return "Kilnmark sample data";
    }

    @Override
    public String family() {
        return "built in";
    }

    @Override
    public String icon() {
        return "folder";
    }

    /** It holds CSV files only. */
    @Override
    public List<String> formats() {
        return List.of("csv");
    }

    @Override
    public List<Setting> settings() {
        return List.of();
    }

    @Override
    public List<String> validate(SourceConfig source) {
        List<String> problems = new ArrayList<>();
        checkReserved(source, problems);
        return problems;
    }

    /** Discovered and read; never written. */
    @Override
    public Set<Capability> capabilities() {
        return Set.of(Capability.DISCOVER, Capability.READ);
    }

    @Override
    public io.kilnmark.kiln.Node writer(SourceConfig source, WriteOptions write) throws ConnectorException {
        throw new ConnectorException("source: Kilnmark's sample data cannot be written to; write to a source "
            + "of your own");
    }

    @Override
    public List<io.kilnmark.kiln.SettingSpec> writeOptions() {
        return List.of();
    }

    @Override
    protected FileStore open(SourceConfig source) throws IOException {
        return new BundledFileStore(ROOT);
    }

    @Override
    protected String container(SourceConfig source, String path) {
        return folder(path);
    }
}

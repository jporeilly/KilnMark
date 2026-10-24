package io.kilnmark.kiln.plugins.files;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.IllegalCharsetNameException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import io.kilnmark.kiln.StepSpec;
import io.kilnmark.kiln.connect.AssetFilter;
import io.kilnmark.kiln.connect.AssetInfo;
import io.kilnmark.kiln.connect.Connector;
import io.kilnmark.kiln.connect.ConnectorException;
import io.kilnmark.kiln.connect.SourceConfig;

/**
 * What the folder and bucket connectors share: every file is an asset whose path is its path in
 * the store; CSV, TSV, JSON, JSON lines and Parquet files are read as rows through
 * {@link FileInput}, and anything else is recorded as a document. Files are never bounded: the
 * reader streams them, so reading one in full costs only time.
 */
public abstract class FileSourceConnector implements Connector {

    /** The optional setting naming the encoding of text files, when detection guesses wrong. */
    static final Setting ENCODING = new Setting("encoding", "Text encoding", false,
        "Leave empty to detect (UTF-8, or windows-1252 when a file is not valid UTF-8); for example ISO-8859-15",
        false);

    /** Opens the folder or bucket a source names, after checking the server allows it. */
    protected abstract FileStore open(SourceConfig source) throws IOException;

    /**
     * Opens the store for a check that must end within {@code timeout}: by default as
     * {@link #open(SourceConfig)}; a store reached over the network bounds each request instead.
     */
    protected FileStore open(SourceConfig source, java.time.Duration timeout) throws IOException {
        return open(source);
    }

    /** The container shown for a file: its folder, or bucket and folder. */
    protected abstract String container(SourceConfig source, String path);

    /** What every path in the store starts with ({@code prefix} for a bucket); empty by default. */
    protected String prefix(SourceConfig source) {
        return "";
    }

    /** Containers to choose from are the top-level folders: {@value #TOP} stands for files directly at the top. */
    static final String TOP = "/";

    @Override
    public ContainerKind containerKind() {
        return new ContainerKind("containers", "Folders", "files", "/");
    }

    /**
     * The top-level folder a file is in, below the prefix: {@code raw/2025} for the key
     * {@code raw/2025/orders.csv} under the prefix {@code raw/}; {@value #TOP} for a file at the top.
     */
    @Override
    public String containerOf(SourceConfig source, AssetInfo asset) {
        return top(source, asset.path());
    }

    String top(SourceConfig source, String path) {
        String prefix = prefix(source);
        String rel = path.startsWith(prefix) ? path.substring(prefix.length()) : path;
        int slash = rel.indexOf('/');
        return slash < 0 ? TOP : prefix + rel.substring(0, slash);
    }

    private static String fileName(String path) {
        return path.substring(path.lastIndexOf('/') + 1);
    }

    private void checkSettings(SourceConfig source) throws ConnectorException {
        List<String> problems = new ArrayList<>(validate(source));
        problems.addAll(AssetFilter.problems(source));
        if (!problems.isEmpty()) {
            throw new ConnectorException("Check the settings of " + source.name() + ": " + String.join("; ", problems));
        }
    }

    /** Every top-level folder, with how many files in it the patterns keep: a listing, no file is read. */
    @Override
    public List<ContainerInfo> containers(SourceConfig source) throws ConnectorException {
        return containers(source, null);
    }

    /** As {@link #containers(SourceConfig)}, stopping the listing once {@code timeout} has passed. */
    @Override
    public List<ContainerInfo> containers(SourceConfig source, java.time.Duration timeout) throws ConnectorException {
        checkSettings(source);
        AssetFilter patterns = AssetFilter.of(this, source).allContainers();
        java.time.Instant deadline = timeout == null ? null : java.time.Instant.now().plus(timeout);
        try (FileStore store = timeout == null ? open(source) : open(source, timeout)) {
            Map<String, Long> counts = new TreeMap<>();
            for (FileStore.Entry e : store.list(deadline)) {
                counts.merge(top(source, e.path()), patterns.patterns(e.path(), fileName(e.path())) ? 1L : 0L,
                    Long::sum);
            }
            return counts.entrySet().stream().map(e -> new ContainerInfo(e.getKey(), e.getValue())).toList();
        } catch (IOException e) {
            throw new ConnectorException(source.name() + ": " + e.getMessage(), e);
        } catch (RuntimeException e) {
            throw new ConnectorException(source.name() + ": cannot list its files (" + e
                + "): check the source's settings", e);
        }
    }

    @Override
    public void test(SourceConfig source) throws ConnectorException {
        test(source, null);
    }

    @Override
    public void test(SourceConfig source, java.time.Duration timeout) throws ConnectorException {
        checkSettings(source);
        try (FileStore store = timeout == null ? open(source) : open(source, timeout)) {
            store.probe();
        } catch (IOException e) {
            throw new ConnectorException(source.name() + ": " + e.getMessage(), e);
        } catch (RuntimeException e) {
            throw new ConnectorException(source.name() + ": cannot open it (" + e + "): check the source's settings", e);
        }
    }

    @Override
    public List<AssetInfo> catalog(SourceConfig source) throws ConnectorException {
        checkSettings(source);
        AssetFilter filter = AssetFilter.of(this, source);
        Charset encoding = encoding(source);
        try (FileStore store = open(source)) {
            List<AssetInfo> out = new ArrayList<>();
            for (FileStore.Entry e : store.list()) {
                if (!filter.keeps(top(source, e.path()), e.path(), fileName(e.path()))) {
                    continue; // not chosen: never opened
                }
                String container = container(source, e.path());
                try {
                    out.add(FileInspector.inspect(store, e, container, encoding));
                } catch (StackOverflowError | LinkageError | RuntimeException ex) {
                    // inspect records what it can; anything escaping it must not stop the catalogue
                    out.add(FileInspector.unreadable(e, container, "it could not be inspected (" + ex + ")"));
                }
            }
            return out;
        } catch (IOException e) {
            throw new ConnectorException(source.name() + ": " + e.getMessage(), e);
        } catch (RuntimeException | StackOverflowError | LinkageError e) {
            throw new ConnectorException(source.name() + ": cannot list its files (" + e
                + "): check the source's settings", e);
        }
    }

    @Override
    public StepSpec reader(SourceConfig source, AssetInfo asset, ReadOptions read) {
        Map<String, String> s = new HashMap<>();
        s.put("path", asset.path());
        String format = asset.hints().get("format");
        s.put("format", format == null ? FileFormat.of(asset.path()).id() : format);
        for (String hint : List.of("size", "delimiter", "quote", "sep-line", "encoding")) {
            if (asset.hints().containsKey(hint)) {
                s.put(hint, asset.hints().get(hint));
            }
        }
        if (source.setting("encoding") != null) {
            s.put("encoding", source.setting("encoding"));
        }
        s.put("asset-urn", read.assetUrn());
        s.put("row-limit", Long.toString(read.rowLimit()));
        s.put("batch-size", Integer.toString(read.batchSize()));
        List<FileColumn> columns = asset.fields().stream().map(FileColumn::of).toList();
        return new StepSpec("read", new FileInput(() -> open(source), columns), s);
    }

    /** The encoding a source's setting names, or null to detect it. */
    static Charset encoding(SourceConfig source) {
        String name = source.setting("encoding");
        return name == null ? null : Charset.forName(name);
    }

    /** Problems with the encoding setting. */
    static void checkEncoding(SourceConfig source, List<String> problems) {
        String name = source.setting("encoding");
        if (name == null) {
            return;
        }
        try {
            if (!Charset.isSupported(name)) {
                problems.add("encoding: this server does not know the encoding '" + name + "'; for example UTF-8, "
                    + "windows-1252 or ISO-8859-15");
            }
        } catch (IllegalCharsetNameException e) {
            problems.add("encoding: '" + name + "' is not an encoding name; for example UTF-8 or windows-1252");
        }
    }

    /** The folder part of a path, without a trailing slash; empty at the top. */
    static String folder(String path) {
        int slash = path.lastIndexOf('/');
        return slash < 0 ? "" : path.substring(0, slash);
    }

    /** Problems with a true/false setting. */
    static void checkBoolean(SourceConfig source, String key, List<String> problems) {
        String v = source.setting(key);
        if (v != null && !v.equalsIgnoreCase("true") && !v.equalsIgnoreCase("false")) {
            problems.add(key + ": true or false, not '" + v + "'");
        }
    }
}

package io.kilnmark.kiln.plugins.files;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.IllegalCharsetNameException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import io.kilnmark.kiln.StepSpec;
import io.kilnmark.kiln.connect.AssetInfo;
import io.kilnmark.kiln.connect.Connector;
import io.kilnmark.kiln.connect.ConnectorException;
import io.kilnmark.kiln.connect.SourceConfig;
import io.kilnmark.kiln.plugins.documents.DocumentTypes;

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

    /** Whether to keep the text of documents, or only their properties. */
    static final Setting KEEP_TEXT = new Setting("keep_text", "Keep document text", false,
        "true (the default) stores documents' text in Kilnmark for search and reading. false stores only their "
            + "properties (title, author, pages, language, words) and deletes text stored before at the next scan",
        false);

    /** With keep_text false, whether stewards may have a document's text read from the source to view it. */
    static final Setting READ_TEXT_ON_DEMAND = new Setting("read_text_on_demand", "Read text on demand", false,
        "Only when keep_text is false. true lets stewards view a document's text, read from the source when they "
            + "open it and never stored; false (the default) shows only its properties", false);

    /**
     * A setting only the server sets, on its own copy of a source, for a read a person asked for:
     * the read then uses the document reader kept for such reads, never one a scan needs.
     */
    public static final String ON_DEMAND = "_read_on_demand";

    /** Opens the folder or bucket a source names, after checking the server allows it. */
    protected abstract FileStore open(SourceConfig source) throws IOException;

    /** The container shown for a file: its folder, or bucket and folder. */
    protected abstract String container(SourceConfig source, String path);

    @Override
    public List<AssetInfo> catalog(SourceConfig source) throws ConnectorException {
        List<String> problems = validate(source);
        if (!problems.isEmpty()) {
            throw new ConnectorException("Check the settings of " + source.name() + ": " + String.join("; ", problems));
        }
        Charset encoding = encoding(source);
        try (FileStore store = open(source)) {
            List<AssetInfo> out = new ArrayList<>();
            for (FileStore.Entry e : store.list()) {
                String container = container(source, e.path());
                try {
                    AssetInfo a = FileInspector.inspect(store, e, container, encoding);
                    if (DocumentTypes.workbook(e.path())) {
                        FileInspector.SheetList sheets = FileInspector.sheets(store, e, container);
                        if (sheets.problem() != null) {
                            // listing failed: the scan keeps the sheets it knew (hint sheets-problem)
                            Map<String, String> hints = new HashMap<>(a.hints());
                            hints.put("sheets-problem", sheets.problem());
                            a = new AssetInfo(a.path(), a.kind(), a.container(), a.name(), a.description(), a.fields(),
                                a.primaryKey(), a.bounded(), a.readable(), hints);
                        }
                        out.add(a);
                        out.addAll(sheets.sheets()); // right after their workbook
                    } else {
                        out.add(a);
                    }
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
        if (FileInspector.KIND_DOCUMENT.equals(asset.kind())) {
            Map<String, String> d = new HashMap<>();
            d.put("path", file(asset));
            d.put("asset-urn", read.assetUrn());
            d.put("size", asset.hints().getOrDefault("size", "-1"));
            putVersion(d, asset);
            d.put("keep-text", Boolean.toString(keepText(source)));
            if ("true".equals(source.setting(ON_DEMAND))) {
                d.put("on-demand", "true");
            }
            if (asset.hints().containsKey("owner")) {
                d.put("owner", asset.hints().get("owner"));
            }
            if (asset.hints().containsKey("sheets-problem")) {
                d.put("note", "Its sheets could not be listed this time (" + asset.hints().get("sheets-problem")
                    + "); the sheets shown are from an earlier scan");
            }
            return new StepSpec("read", new DocumentInput(() -> open(source)), d);
        }
        if (FileInspector.KIND_SHEET.equals(asset.kind())) {
            Map<String, String> d = new HashMap<>();
            d.put("path", asset.hints().getOrDefault("file", unescape(asset.hints().getOrDefault("parent", ""))));
            putVersion(d, asset);
            d.put("sheet", asset.hints().getOrDefault("sheet", asset.name()));
            d.put("size", asset.hints().getOrDefault("size", "-1"));
            d.put("asset-urn", read.assetUrn());
            d.put("row-limit", Long.toString(read.rowLimit()));
            d.put("batch-size", Integer.toString(read.batchSize()));
            return new StepSpec("read", new SheetInput(() -> open(source),
                asset.fields().stream().map(FileColumn::of).toList()), d);
        }
        Map<String, String> s = new HashMap<>();
        s.put("path", file(asset));
        String format = asset.hints().get("format");
        s.put("format", format == null ? FileFormat.of(file(asset)).id() : format);
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

    /**
     * A file's path as an asset path: {@code %} and {@code #} escaped ({@code %25}, {@code %23}),
     * so {@code #} in a URN only ever separates a file from something inside it (a sheet, an
     * attachment) and a file named {@code book.xlsx#Data} cannot pass for a sheet.
     */
    public static String urnPath(String path) {
        return path.replace("%", "%25").replace("#", "%23");
    }

    /** The file path an asset path names: {@link #urnPath} undone. */
    public static String unescape(String path) {
        return path.replace("%23", "#").replace("%25", "%");
    }

    /** The path in the store of the file an asset is (or is inside of, for a sheet: its workbook). */
    static String file(AssetInfo asset) {
        String file = asset.hints().get("file");
        return file != null ? file : unescape(asset.path());
    }

    private static void putVersion(Map<String, String> settings, AssetInfo asset) {
        String v = LocalCopy.version(asset.hints().getOrDefault("size", ""), asset.hints().get("modified"),
            asset.hints().get("etag"));
        if (v != null) {
            settings.put("version", v);
        }
    }

    /** A scan of a source has ended: shared copies of its workbooks nobody is using are deleted. */
    @Override
    public void finished(SourceConfig source) {
        try (FileStore store = open(source)) {
            LocalCopy.dropIdle(store.copyScope());
        } catch (IOException | RuntimeException e) {
            // the copies go after ten minutes unused anyway
        }
    }

    /** Whether a source keeps the text of its documents (setting {@code keep_text}, default true). */
    public static boolean keepText(SourceConfig source) {
        return !"false".equalsIgnoreCase(source.setting("keep_text"));
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

    /** The server's own settings ({@link #ON_DEMAND}) may not be given by people adding sources. */
    static void checkReserved(SourceConfig source, List<String> problems) {
        if (source.setting(ON_DEMAND) != null) {
            problems.add(ON_DEMAND + ": reserved for the server; use read_text_on_demand");
        }
    }

    /** Problems with a true/false setting. */
    static void checkBoolean(SourceConfig source, String key, List<String> problems) {
        String v = source.setting(key);
        if (v != null && !v.equalsIgnoreCase("true") && !v.equalsIgnoreCase("false")) {
            problems.add(key + ": true or false, not '" + v + "'");
        }
    }
}

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

    /** The optional setting naming the delimiter of delimited files, when detection guesses wrong. */
    static final Setting DELIMITER = new Setting("delimiter", "Delimiter", false,
        "Leave empty to detect it (comma, semicolon, tab or |). Write tab as \\t", false);

    /** The optional setting saying delimited files have no header row. */
    static final Setting HEADER = Setting.bool("header", "Header row", true,
        "true (the default): the first row names the columns. false: it is data, and columns are named "
            + "column_1, column_2 and so on");

    /** The formats read as rows (Excel sheets as tables), and documents read as text. */
    static final List<String> FORMATS = List.of("csv", "tsv", "json", "jsonl", "parquet", "excel", "documents");

    @Override
    public String category() {
        return "files";
    }

    @Override
    public List<String> formats() {
        return FORMATS;
    }

    /** The text options a source's settings give; null fields are detected. */
    static FileInspector.TextOptions options(SourceConfig source) {
        String d = source.setting("delimiter");
        String h = source.setting("header");
        return new FileInspector.TextOptions(encoding(source), d == null ? null : Csv.Dialect.parse(d, ','),
            h == null ? null : Boolean.parseBoolean(h));
    }

    /** Problems with the delimiter and header settings. */
    static void checkText(SourceConfig source, List<String> problems) {
        String d = source.setting("delimiter");
        if (d != null && !(d.length() == 1 || d.equals("\\t"))) {
            problems.add("delimiter: one character, such as ; or |, or \\t for a tab; not '" + d + "'");
        } else if (d != null && (d.equals("\"") || d.equals("'"))) {
            problems.add("delimiter: a quote character cannot be the delimiter");
        }
        checkBoolean(source, "header", problems);
    }

    /** Whether to keep the text of documents, or only their properties. */
    static final Setting KEEP_TEXT = Setting.bool("keep_text", "Keep document text", true,
        "true (the default) stores documents' text in Kilnmark for search and reading. false stores only their "
            + "properties (title, author, pages, language, words) and deletes text stored before at the next scan");

    /** With keep_text false, whether stewards may have a document's text read from the source to view it. */
    static final Setting READ_TEXT_ON_DEMAND = Setting.bool("read_text_on_demand", "Read text on demand", false,
        "Only when keep_text is false. true lets stewards view a document's text, read from the source when they "
            + "open it and never stored; false (the default) shows only its properties");

    /**
     * A setting only the server sets, on its own copy of a source, for a read a person asked for:
     * the read then uses the document reader kept for such reads, never one a scan needs.
     */
    public static final String ON_DEMAND = "_read_on_demand";

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

    static String fileName(String path) {
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
        FileInspector.TextOptions options = options(source);
        try (FileStore store = open(source)) {
            List<AssetInfo> out = new ArrayList<>();
            for (FileStore.Entry e : store.list()) {
                if (!filter.keeps(top(source, e.path()), e.path(), fileName(e.path()))) {
                    continue; // not chosen: never opened
                }
                String container = container(source, e.path());
                try {
                    AssetInfo a = FileInspector.inspect(store, e, container, options);
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

    /** The files in one top-level folder, from a listing; none is opened. */
    @Override
    public List<AssetInfo> assets(SourceConfig source, String container) throws ConnectorException {
        checkSettings(source);
        try (FileStore store = open(source)) {
            List<AssetInfo> out = new ArrayList<>();
            for (FileStore.Entry e : store.list()) {
                if (top(source, e.path()).equals(container)) {
                    FileFormat f = FileFormat.of(fileName(e.path()));
                    out.add(new AssetInfo(e.path(), f.readable() ? FileInspector.KIND_FILE : FileInspector.KIND_DOCUMENT,
                        container(source, e.path()), fileName(e.path()), null, List.of(), List.of(), false,
                        f.readable(), Map.of("format", f.id(), "size", Long.toString(e.size()))));
                }
            }
            return out;
        } catch (IOException e) {
            throw new ConnectorException(source.name() + ": " + e.getMessage(), e);
        }
    }

    /** One file, inspected as the catalogue would. */
    @Override
    public AssetInfo asset(SourceConfig source, String path) throws ConnectorException {
        checkSettings(source);
        try (FileStore store = open(source)) {
            FileStore.Entry e = store.entry(path);
            return FileInspector.inspect(store, e, container(source, path), options(source));
        } catch (IOException e) {
            throw new ConnectorException(source.name() + ": " + e.getMessage(), e);
        }
    }

    /** The rows, and what was detected about the file: format, delimiter, header, encoding, records. */
    @Override
    public io.kilnmark.kiln.connect.Preview preview(SourceConfig source, AssetInfo asset, int rows)
        throws ConnectorException {
        io.kilnmark.kiln.connect.Preview p = io.kilnmark.kiln.connect.Preview.read(this, source, asset, rows);
        Map<String, String> details = new java.util.TreeMap<>();
        for (String key : List.of("format", "delimiter", "quote", "header", "encoding", "sep-line", "records")) {
            if (asset.hints().containsKey(key)) {
                details.put(key, asset.hints().get(key));
            }
        }
        return new io.kilnmark.kiln.connect.Preview(p.columns(), p.rows(), p.truncated(), details);
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
        for (String key : List.of("encoding", "delimiter", "header")) {
            if (source.setting(key) != null) {
                s.put(key, source.setting(key)); // the source's settings win over what was detected
            }
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

    /** Discover and read, and write CSV and Parquet files (docs/guide/writing-data.md). */
    @Override
    public java.util.Set<Capability> capabilities() {
        return java.util.Set.of(Capability.DISCOVER, Capability.READ, Capability.WRITE);
    }

    /**
     * A step writing one CSV or Parquet file at {@code write.target()}, a path inside the source
     * (below an S3 source's prefix); see {@link FileTarget} for what is refused. Mode {@code create}
     * (or none) refuses an existing file unless the setting {@code overwrite} is true;
     * {@code replace} replaces it. Further settings: {@code overwrite}, {@code delimiter} (CSV),
     * {@code compression} (Parquet: snappy, zstd, gzip or none), {@code allow_unscanned},
     * {@code profile_output} and
     * {@code keep_values}.
     */
    @Override
    public io.kilnmark.kiln.Step writer(SourceConfig source, WriteOptions write) throws ConnectorException {
        checkSettings(source);
        String mode = write.mode() == null || write.mode().isBlank() ? "create"
            : write.mode().strip().toLowerCase(java.util.Locale.ROOT);
        io.kilnmark.kiln.plugins.write.WriteSettings settings =
            new io.kilnmark.kiln.plugins.write.WriteSettings(write.options());
        boolean overwrite = switch (mode) {
            case "create" -> settings.bool("overwrite", false);
            case "replace" -> {
                if (!settings.bool("overwrite", true)) {
                    throw new ConnectorException("overwrite: false contradicts mode replace; use mode create to "
                        + "refuse an existing file");
                }
                yield true;
            }
            case "append", "upsert" -> throw new ConnectorException("mode: files are written whole, so " + mode
                + " is for database tables; use create (a new file) or replace (replace the file)");
            default -> throw new ConnectorException("mode: create or replace for files, not '" + mode + "'");
        };
        if (!write.key().isEmpty()) {
            throw new ConnectorException("key: only upsert into a database table takes key columns");
        }
        FileTarget target = FileTarget.of(write.target(), write.format());
        String storePath = storePath(source, target);
        if (!settings.bool("allow_unscanned", false)) {
            String unseen = unscanned(source, storePath);
            if (unseen != null) {
                throw new ConnectorException("path: " + target.path() + " would not be found by scans of "
                    + source.name() + ", because " + unseen + ". Write somewhere the source scans, change the source's "
                    + "settings, or set allow_unscanned: true to write it anyway");
            }
        }
        String delimiter = csvDelimiter(source, target, settings);
        return new FileOutput(() -> open(source), storePath, target, overwrite, settings, write.assetUrn(),
            write.profiling(), delimiter, options(source));
    }

    /**
     * The delimiter a CSV file is written with: the step's, else the source's own, else a comma.
     * A CSV file must be one the source reads back as written, so refused: a delimiter other than
     * the source's, a source whose CSV files have no header row, and a source reading another
     * encoding than UTF-8, the one files are written in.
     */
    static String csvDelimiter(SourceConfig source, FileTarget target,
                               io.kilnmark.kiln.plugins.write.WriteSettings settings)
        throws ConnectorException {
        String own = source.setting("delimiter");
        String step = settings.text("delimiter", null);
        if (!target.format().equals("csv")) {
            return step;
        }
        if (own != null && step != null && CsvEncoder.delimiter(own) != CsvEncoder.delimiter(step)) {
            throw new ConnectorException("delimiter: " + source.name() + " reads files delimited by '" + own
                + "', so a file written with '" + step + "' would be read wrongly; leave delimiter out to use the "
                + "source's");
        }
        if ("false".equalsIgnoreCase(source.setting("header"))) {
            throw new ConnectorException("path: " + source.name() + " reads CSV files without a header row, and "
                + "Kilnmark writes one; write Parquet, or to a source that reads header rows");
        }
        String encoding = source.setting("encoding");
        if (encoding != null && Charset.isSupported(encoding)
            && !Charset.forName(encoding).equals(java.nio.charset.StandardCharsets.UTF_8)) {
            throw new ConnectorException("path: " + source.name() + " reads text files as " + encoding + ", and "
                + "Kilnmark writes CSV in UTF-8; write Parquet, or to a source reading UTF-8");
        }
        return step != null ? step : own;
    }

    /** Where the store puts a written file: below an S3 source's prefix, as a folder or key path. */
    private String storePath(SourceConfig source, FileTarget target) {
        String prefix = prefix(source);
        String under = prefix.isEmpty() || prefix.endsWith("/") ? prefix : prefix + "/";
        return under + target.path();
    }

    /** The written file's asset path, as the catalogue gives it: below the prefix, with % and # escaped. */
    @Override
    public String writtenAssetPath(SourceConfig source, WriteOptions write) throws ConnectorException {
        return urnPath(storePath(source, FileTarget.of(write.target(), write.format())));
    }

    /** The settings a file write takes; see {@link #writer}. */
    @Override
    public List<io.kilnmark.kiln.SettingSpec> writeOptions() {
        return io.kilnmark.kiln.plugins.write.WriteSettings.withProfiling(
            io.kilnmark.kiln.plugins.write.WriteSettings.flag("overwrite", "Overwrite", false,
                "Replace a file of the same name; otherwise an existing file stops the write"),
            io.kilnmark.kiln.SettingSpec.optional("delimiter", "Delimiter", io.kilnmark.kiln.SettingSpec.Kind.TEXT, null,
                "CSV only: one character, or \\t for a tab; by default the source's own delimiter, else a comma. A "
                    + "delimiter other than the source's is refused"),
            io.kilnmark.kiln.SettingSpec.choice("compression", "Compression", false, "snappy",
                "Parquet only: how pages are compressed", List.of("snappy", "zstd", "gzip", "none")),
            io.kilnmark.kiln.plugins.write.WriteSettings.flag("allow_unscanned", "Allow paths scans skip", false,
                "Write even where scans of this source would not find the file"));
    }

    /**
     * Why a scan of {@code source} would not find the file at {@code path} (its store path), or null
     * when it would: the source does not look in sub-folders, or the folder or name is not among
     * those the source's containers and patterns keep.
     */
    String unscanned(SourceConfig source, String path) {
        if ("false".equalsIgnoreCase(source.setting("recursive")) && path.substring(prefix(source).length())
            .contains("/")) {
            return "the source does not look in sub-folders (recursive is false)";
        }
        AssetFilter filter = AssetFilter.of(this, source);
        if (!filter.keeps(top(source, path), path, fileName(path))) {
            return "it is outside the folders or patterns the source scans (its containers, include and exclude "
                + "settings)";
        }
        return null;
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

package io.kilnmark.server.api;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import io.kilnmark.graph.Urn;
import io.kilnmark.graph.store.DocumentRecords;
import io.kilnmark.graph.store.DocumentStore;
import io.kilnmark.graph.store.GraphStore;
import io.kilnmark.graph.store.Records;
import io.kilnmark.kiln.Pipeline;
import io.kilnmark.kiln.RunResult;
import io.kilnmark.kiln.Runner;
import io.kilnmark.kiln.StepSpec;
import io.kilnmark.kiln.connect.AssetInfo;
import io.kilnmark.kiln.connect.Connector;
import io.kilnmark.kiln.connect.Connectors;
import io.kilnmark.kiln.connect.SourceConfig;
import io.kilnmark.server.KilnmarkConfig;
import io.kilnmark.server.SecretBox;
import io.kilnmark.server.documents.ChunkRows;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.core.Response;

/**
 * A document's text for reading: put together from its stored chunks or, for a source that keeps
 * no text ({@code keep_text=false}), read again from the source for the person asking, through the
 * same reader and limits as a scan. Text read again is held in memory for a few minutes (a few
 * documents at most) so turning pages does not read the document each time; it is never stored.
 */
@ApplicationScoped
public class DocumentTexts {

    /** Characters in a section of a document that has no pages. */
    static final int SECTION = 20_000;

    private static final Duration HOLD = Duration.ofMinutes(5);
    private static final int HELD = 4;

    /** A document's text and where its pages start. {@code pageKind}: page, slide, sheet or part. */
    record Text(String text, List<DocumentRecords.Page> pages, String pageKind, boolean truncated,
                boolean extractedNow) {
    }

    private record Held(Instant at, List<DocumentRecords.Chunk> chunks) {
    }

    private final GraphStore store;
    private final DocumentStore documents;
    private final Connectors connectors;
    private final SecretBox secrets;
    private final KilnmarkConfig config;
    private final Map<String, Held> held = new LinkedHashMap<>(16, 0.75f, true);

    public DocumentTexts(GraphStore store, DocumentStore documents, Connectors connectors, SecretBox secrets,
                         KilnmarkConfig config) {
        this.store = store;
        this.documents = documents;
        this.connectors = connectors;
        this.secrets = secrets;
        this.config = config;
    }

    /** The document's profile, or the reason there is no text to show (as an API error). */
    DocumentRecords.Document readable(String urn) {
        Records.AssetDetail a = store.asset(urn)
            .orElseThrow(() -> ApiError.of(Response.Status.NOT_FOUND, "No asset " + urn));
        if (!"document".equals(a.kind())) {
            throw new IllegalArgumentException(a.name() + " is a " + a.kind() + ", not a document: it has no text to "
                + "read");
        }
        DocumentRecords.Document d = documents.document(urn).orElseThrow(() -> ApiError.of(Response.Status.NOT_FOUND,
            a.name() + " has not been read yet: scan its source, then try again"));
        if (!d.readable()) {
            throw ApiError.of(Response.Status.CONFLICT, "Kilnmark could not read " + a.name() + ": " + d.problem());
        }
        return d;
    }

    /** The text of a readable document. */
    Text text(String urn) {
        DocumentRecords.Document d = readable(urn);
        boolean now = !d.textKept();
        List<DocumentRecords.Chunk> chunks = now ? extract(urn) : documents.chunks(urn, 0, Integer.MAX_VALUE);
        String text = join(chunks);
        List<DocumentRecords.Page> pages = d.pageList();
        String kind = kind(d);
        if (pages.isEmpty()) {
            pages = sections(text);
            kind = "part";
        }
        return new Text(text, pages, kind, d.truncated(), now);
    }

    private static String kind(DocumentRecords.Document d) {
        String f = d.format() == null ? "" : d.format();
        return switch (f) {
            case "xlsx", "xlsm", "xls", "ods" -> "sheet";
            case "pptx", "pptm", "ppt", "odp" -> "slide";
            default -> "page";
        };
    }

    /**
     * The text the chunks came from: each chunk at its own offsets, the space between chunks
     * (always white space) as line breaks, so page offsets still point at the right place.
     */
    static String join(List<DocumentRecords.Chunk> chunks) {
        StringBuilder b = new StringBuilder();
        List<DocumentRecords.Chunk> sorted = new ArrayList<>(chunks);
        sorted.sort(Comparator.comparingInt(DocumentRecords.Chunk::sequence));
        for (DocumentRecords.Chunk c : sorted) {
            if (c.start() >= b.length()) {
                b.append("\n".repeat(c.start() - b.length())).append(c.text());
            } else if (c.end() > b.length()) {
                b.append(c.text(), b.length() - c.start(), c.text().length());
            }
        }
        return b.toString();
    }

    /** Parts of about {@value #SECTION} characters, ending at a paragraph where possible. */
    static List<DocumentRecords.Page> sections(String text) {
        List<DocumentRecords.Page> out = new ArrayList<>();
        int start = 0;
        int n = 1;
        do {
            out.add(new DocumentRecords.Page(n++, start, null));
            int end = start + SECTION;
            if (end >= text.length()) {
                break;
            }
            int brk = text.lastIndexOf("\n\n", end);
            start = brk > start + SECTION / 2 ? brk + 2 : end;
        } while (start < text.length());
        return out;
    }

    /** The text of page {@code number} (1-based). */
    static String page(Text t, int number) {
        int start = Math.min(t.pages().get(number - 1).start(), t.text().length());
        int end = number < t.pages().size() ? Math.min(t.pages().get(number).start(), t.text().length())
            : t.text().length();
        return end <= start ? "" : t.text().substring(start, end).strip();
    }

    /** Reads a document from its source again, keeping its chunks in memory only. */
    private List<DocumentRecords.Chunk> extract(String urn) {
        synchronized (held) {
            Held h = held.get(urn);
            if (h != null && h.at().plus(HOLD).isAfter(Instant.now())) {
                return h.chunks();
            }
        }
        Urn u = Urn.parse(urn);
        Records.Source source = store.source(u.source()).orElseThrow(() -> ApiError.of(Response.Status.NOT_FOUND,
            "The source " + u.source() + " no longer exists"));
        if (!allowed(source)) {
            throw ApiError.of(Response.Status.FORBIDDEN, "You may not read documents from " + source.name());
        }
        Connector connector = connectors.get(source.kind()).orElseThrow(() -> ApiError.of(Response.Status.CONFLICT,
            "This server has no connector for sources of kind '" + source.kind() + "'"));
        // an attachment is read from its message
        Optional<DocumentRecords.Part> parent = documents.parent(urn);
        String readUrn = parent.filter(p -> "document".equals(p.kind())).map(DocumentRecords.Part::urn).orElse(urn);
        String path = Urn.parse(readUrn).path();
        Map<String, String> settings = new HashMap<>(source.settings());
        settings.put("keep_text", "true"); // only for this read; nothing is stored
        String secret = store.sourceSecret(source.name()).map(secrets::open).orElse(null);
        SourceConfig config = new SourceConfig(source.name(), source.kind(), settings, secret);
        String name = path.substring(path.lastIndexOf('/') + 1);
        AssetInfo asset = new AssetInfo(path, "document", "", name, null, List.of(), List.of(), false, true, Map.of());
        Map<String, List<DocumentRecords.Chunk>> chunks = new HashMap<>();
        StepSpec reader = connector.reader(config, asset, new Connector.ReadOptions(readUrn, 0, 256));
        RunResult r;
        try {
            r = new Runner(this.config.scan().memoryLimitMb() << 20, 4).run(new Pipeline("read-" + path, List.of(reader,
                new StepSpec("collect", new ChunkRows((k, c) -> {
                    synchronized (chunks) {
                        chunks.computeIfAbsent(k, x -> new ArrayList<>()).add(c);
                    }
                }, () -> { }), Map.of()))), e -> { });
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw ApiError.of(Response.Status.SERVICE_UNAVAILABLE, "The server is stopping: try again shortly");
        }
        if (!r.succeeded()) {
            throw ApiError.of(Response.Status.BAD_GATEWAY, "Could not read " + name + " from " + source.name()
                + " just now: " + r.error());
        }
        List<DocumentRecords.Chunk> mine;
        synchronized (chunks) {
            mine = List.copyOf(chunks.getOrDefault(urn, List.of()));
        }
        synchronized (held) {
            held.put(urn, new Held(Instant.now(), mine));
            while (held.size() > HELD) {
                held.remove(held.keySet().iterator().next());
            }
        }
        return mine;
    }

    /**
     * Whether the person asking may read documents from this source. Every viewer may read every
     * source today; per-source access arrives with stream A's source settings, and goes here.
     */
    boolean allowed(Records.Source source) {
        return true;
    }
}

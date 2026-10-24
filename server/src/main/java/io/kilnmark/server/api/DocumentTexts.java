package io.kilnmark.server.api;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
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
import io.kilnmark.server.auth.AccessRole;
import io.kilnmark.server.documents.ChunkRows;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.core.Response;

/**
 * A document's text for reading, page by page: put together from the stored chunks that cover the
 * page asked for. For a source that keeps no text ({@code keep_text=false}) the text is shown only
 * when the source also has {@code read_text_on_demand=true}, and then only to stewards: it is read
 * from the source for that person, on the server's one reader kept for such reads (never one a
 * scan needs), at most {@value #PER_MINUTE} documents a minute per person, and held in memory for a
 * few minutes (four documents at most), never stored.
 */
@ApplicationScoped
public class DocumentTexts {

    /** Chunks in a part of a document that has no pages. */
    static final int PART_CHUNKS = 12;

    /** Reads from sources one person may ask for in a minute. */
    static final int PER_MINUTE = 6;

    /** The source setting that allows reading text on demand. */
    public static final String ON_DEMAND = "read_text_on_demand";

    private static final Duration HOLD = Duration.ofMinutes(5);
    private static final int HELD = 4;

    /** The pages of a document and how to find each one's chunks. {@code byChunks}: parts of chunks. */
    record Layout(DocumentRecords.Document document, List<DocumentRecords.Page> pages, String pageKind,
                  boolean byChunks, boolean onDemand) {
    }

    private record Held(Instant at, List<DocumentRecords.Chunk> chunks) {
    }

    private final GraphStore store;
    private final DocumentStore documents;
    private final Connectors connectors;
    private final SecretBox secrets;
    private final KilnmarkConfig config;
    private final SecurityIdentity identity;
    private final Map<String, Held> held = new LinkedHashMap<>(16, 0.75f, true);
    private final Map<String, Deque<Instant>> asked = new HashMap<>();

    public DocumentTexts(GraphStore store, DocumentStore documents, Connectors connectors, SecretBox secrets,
                         KilnmarkConfig config, SecurityIdentity identity) {
        this.store = store;
        this.documents = documents;
        this.connectors = connectors;
        this.secrets = secrets;
        this.config = config;
        this.identity = identity;
    }

    /** Whether a source lets its documents' text be read on demand (setting {@value #ON_DEMAND}). */
    public static boolean onDemand(Map<String, String> settings) {
        return "true".equalsIgnoreCase(settings.get(ON_DEMAND));
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

    /** How a readable document's text is divided, after checking the person may see it. */
    Layout layout(String urn) {
        DocumentRecords.Document d = readable(urn);
        boolean now = !d.textKept();
        if (now) {
            Records.Source source = source(urn);
            if (!onDemand(source.settings())) {
                throw ApiError.of(Response.Status.CONFLICT, "Kilnmark keeps no text for documents in "
                    + source.name() + " (keep_text is off), so only their properties are shown. An administrator "
                    + "can set " + ON_DEMAND + " on the source to let stewards read text from the source.");
            }
            if (!allowed(source)) {
                throw ApiError.of(Response.Status.FORBIDDEN, "Only stewards may have text read from " + source.name()
                    + ", because Kilnmark keeps none for it");
            }
        }
        String kind = kind(d);
        if (!d.pageList().isEmpty()) {
            return new Layout(d, d.pageList(), kind, false, now);
        }
        int count = now ? extract(urn).size() : d.chunkCount();
        List<DocumentRecords.Page> parts = new ArrayList<>();
        for (int i = 0; i == 0 || i * PART_CHUNKS < count; i++) {
            parts.add(new DocumentRecords.Page(i + 1, i * PART_CHUNKS, null));
        }
        return new Layout(d, parts, "part", true, now);
    }

    private static String kind(DocumentRecords.Document d) {
        String f = d.format() == null ? "" : d.format();
        return switch (f) {
            case "xlsx", "xlsm", "xls", "ods" -> "sheet";
            case "pptx", "pptm", "ppt", "odp" -> "slide";
            default -> "page";
        };
    }

    /** The text of page {@code n} (1-based), loading only the chunks that cover it. */
    String page(String urn, Layout l, int n) {
        if (l.byChunks()) {
            // part n is chunks [first, first + PART_CHUNKS); the chunk before is fetched to cut its overlap
            int first = (n - 1) * PART_CHUNKS;
            List<DocumentRecords.Chunk> chunks = l.onDemand()
                ? slice(extract(urn), Math.max(0, first - 1), first + PART_CHUNKS)
                : documents.chunks(urn, Math.max(0, first - 1), PART_CHUNKS + (first > 0 ? 1 : 0));
            int from = first > 0 && !chunks.isEmpty() ? chunks.get(0).end() : 0;
            List<DocumentRecords.Chunk> mine = first > 0 && !chunks.isEmpty() ? chunks.subList(1, chunks.size()) : chunks;
            if (mine.isEmpty()) {
                return "";
            }
            int to = mine.get(mine.size() - 1).end();
            return cut(mine, Math.max(from, mine.get(0).start()), to);
        }
        int from = l.pages().get(n - 1).start();
        int to = n < l.pages().size() ? l.pages().get(n).start() : Integer.MAX_VALUE;
        List<DocumentRecords.Chunk> chunks = l.onDemand()
            ? extract(urn).stream().filter(c -> c.end() > from && c.start() < to).toList()
            : documents.chunksBetween(urn, from, to);
        return cut(chunks, from, to);
    }

    /** Every page's text, loading the chunks once (for find). */
    List<String> pages(String urn, Layout l) {
        List<DocumentRecords.Chunk> all = l.onDemand() ? extract(urn) : documents.chunks(urn, 0, Integer.MAX_VALUE);
        List<String> out = new ArrayList<>();
        for (int n = 1; n <= l.pages().size(); n++) {
            if (l.byChunks()) {
                int first = (n - 1) * PART_CHUNKS;
                List<DocumentRecords.Chunk> mine = slice(all, first, first + PART_CHUNKS);
                if (mine.isEmpty()) {
                    out.add("");
                    continue;
                }
                int from = first > 0 ? all.get(first - 1).end() : 0;
                out.add(cut(mine, Math.max(from, mine.get(0).start()), mine.get(mine.size() - 1).end()));
            } else {
                int from = l.pages().get(n - 1).start();
                int to = n < l.pages().size() ? l.pages().get(n).start() : Integer.MAX_VALUE;
                out.add(cut(all.stream().filter(c -> c.end() > from && c.start() < to).toList(), from, to));
            }
        }
        return out;
    }

    private static List<DocumentRecords.Chunk> slice(List<DocumentRecords.Chunk> all, int from, int to) {
        return all.subList(Math.min(from, all.size()), Math.min(to, all.size()));
    }

    /**
     * Characters {@code from} to {@code to} of the text the chunks came from: each chunk at its own
     * offsets, the space between chunks (always white space) as line breaks.
     */
    static String cut(List<DocumentRecords.Chunk> chunks, int from, int to) {
        if (chunks.isEmpty()) {
            return "";
        }
        List<DocumentRecords.Chunk> sorted = new ArrayList<>(chunks);
        sorted.sort(Comparator.comparingInt(DocumentRecords.Chunk::sequence));
        int base = sorted.get(0).start();
        StringBuilder b = new StringBuilder();
        for (DocumentRecords.Chunk c : sorted) {
            int at = c.start() - base;
            if (at >= b.length()) {
                b.append("\n".repeat(at - b.length())).append(c.text());
            } else if (at + c.text().length() > b.length()) {
                b.append(c.text(), b.length() - at, c.text().length());
            }
        }
        int start = Math.max(0, Math.min(b.length(), from - base));
        int end = (int) Math.max(start, Math.min((long) b.length(), (long) to - base));
        return b.substring(start, end).strip();
    }

    /** The source a document belongs to. */
    private Records.Source source(String urn) {
        Urn u = Urn.parse(urn);
        return store.source(u.source()).orElseThrow(() -> ApiError.of(Response.Status.NOT_FOUND,
            "The source " + u.source() + " no longer exists"));
    }

    /** Reads a document from its source again, keeping its chunks in memory only. */
    List<DocumentRecords.Chunk> extract(String urn) {
        synchronized (held) {
            Held h = held.get(urn);
            if (h != null && h.at().plus(HOLD).isAfter(Instant.now())) {
                return h.chunks();
            }
        }
        Records.Source source = source(urn);
        if (!onDemand(source.settings()) || !allowed(source)) {
            throw ApiError.of(Response.Status.FORBIDDEN, "Text from " + source.name() + " is not read on demand");
        }
        limitRate();
        Connector connector = connectors.get(source.kind()).orElseThrow(() -> ApiError.of(Response.Status.CONFLICT,
            "This server has no connector for sources of kind '" + source.kind() + "'"));
        // an attachment is read from its message
        Optional<DocumentRecords.Part> parent = documents.parent(urn);
        String readUrn = parent.filter(p -> "document".equals(p.kind())).map(DocumentRecords.Part::urn).orElse(urn);
        String path = Urn.parse(readUrn).path();
        Map<String, String> settings = new HashMap<>(source.settings());
        settings.put("keep_text", "true"); // only for this read; nothing is stored
        settings.put(io.kilnmark.kiln.plugins.files.FileSourceConnector.ON_DEMAND, "true");
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
            mine = new ArrayList<>(chunks.getOrDefault(urn, List.of()));
        }
        mine.sort(Comparator.comparingInt(DocumentRecords.Chunk::sequence));
        synchronized (held) {
            held.put(urn, new Held(Instant.now(), List.copyOf(mine)));
            while (held.size() > HELD) {
                held.remove(held.keySet().iterator().next());
            }
        }
        return mine;
    }

    /** At most {@value #PER_MINUTE} reads from sources per person per minute. */
    private void limitRate() {
        String who = identity == null || identity.isAnonymous() ? "anonymous" : identity.getPrincipal().getName();
        Instant now = Instant.now();
        synchronized (asked) {
            Deque<Instant> times = asked.computeIfAbsent(who, k -> new ArrayDeque<>());
            while (!times.isEmpty() && times.peekFirst().isBefore(now.minusSeconds(60))) {
                times.pollFirst();
            }
            if (times.size() >= PER_MINUTE) {
                throw ApiError.of(Response.Status.TOO_MANY_REQUESTS, "You have had " + PER_MINUTE + " documents read "
                    + "from their sources in the last minute: wait a minute, then try again");
            }
            times.addLast(now);
        }
    }

    /**
     * Whether the person asking may have text read from this source. Until per-source access exists,
     * stewards may and viewers may not.
     */
    boolean allowed(Records.Source source) {
        // TODO(per-source access): check the person's access to this source here when sources get it
        return identity != null && identity.hasRole(AccessRole.STEWARD_ROLE);
    }
}

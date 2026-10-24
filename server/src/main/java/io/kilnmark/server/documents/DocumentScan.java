package io.kilnmark.server.documents;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.kilnmark.graph.Urn;
import io.kilnmark.graph.store.DocumentRecords;
import io.kilnmark.graph.store.DocumentStore;
import io.kilnmark.graph.store.GraphStore;
import io.kilnmark.graph.store.JdbcDocumentStore;
import io.kilnmark.graph.store.Records;
import io.kilnmark.kiln.MetadataEvent;
import io.kilnmark.kiln.Pipeline;
import io.kilnmark.kiln.RunResult;
import io.kilnmark.kiln.Runner;
import io.kilnmark.kiln.StepSpec;
import io.kilnmark.kiln.connect.AssetInfo;
import io.kilnmark.kiln.connect.Connector;
import io.kilnmark.kiln.connect.SourceConfig;
import io.kilnmark.kiln.plugins.documents.DocumentEvents;
import io.kilnmark.kiln.plugins.documents.Extraction;
import io.kilnmark.server.KilnmarkConfig;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * The document part of a scan: reads a document through its connector (text, profile, chunks) and
 * stores what it found, including a mail message's attachments as assets inside it. Also links a
 * workbook's sheets to the workbook. {@code ScanService} calls it for every document asset.
 */
@ApplicationScoped
public class DocumentScan {

    private static final org.jboss.logging.Logger LOG = org.jboss.logging.Logger.getLogger(DocumentScan.class);

    /** Kind of the assets read as documents. */
    public static final String KIND = "document";

    private final GraphStore store;
    private final DocumentStore documents;
    private final KilnmarkConfig config;
    private final ObjectMapper json;

    public DocumentScan(GraphStore store, DocumentStore documents, KilnmarkConfig config, ObjectMapper json) {
        this.store = store;
        this.documents = documents;
        this.config = config;
        this.json = json;
    }

    /** Whether the scan reads this asset as a document, rather than profiling it as a table. */
    public boolean handles(AssetInfo a) {
        return KIND.equals(a.kind()) && a.readable();
    }

    /**
     * After an asset is saved: an asset inside another (a sheet) is linked to it, and when a
     * workbook's sheets could not be listed this time, the sheets an earlier scan found are kept.
     */
    public void recorded(String source, long runId, String urn, AssetInfo a) {
        String parent = a.hints().get("parent");
        if (parent != null && !parent.isBlank()) {
            documents.setParent(urn, new Urn(source, parent).toString());
        }
        if (a.hints().containsKey("sheets-problem")) {
            documents.keepParts(urn, runId, "sheet");
        }
    }

    /** Thrown inside a document pipeline to end it once the scan is stopped. */
    static final class Stopped extends RuntimeException {
        @java.io.Serial
        private static final long serialVersionUID = 1L;

        Stopped() {
            super("The scan was stopped", null, false, false);
        }
    }

    /**
     * Reads one document and stores its profile, attachments and chunks (replacing any from earlier
     * scans; none when the source keeps no text).
     */
    public RunResult read(Connector connector, SourceConfig source, AssetInfo a, String urn, long sourceId, long runId,
                          BooleanSupplier stop) throws InterruptedException {
        boolean keepText = !"false".equalsIgnoreCase(source.setting("keep_text"));
        Set<String> read = ConcurrentHashMap.newKeySet();
        Map<String, List<DocumentRecords.Chunk>> chunks = new ConcurrentHashMap<>();
        StepSpec reader = connector.reader(source, a, new Connector.ReadOptions(urn, 0, 256));
        StepSpec sink = new StepSpec("store-text", new ChunkRows(
            (u, c) -> chunks.computeIfAbsent(u, k -> new ArrayList<>()).add(c),
            () -> read.forEach(u -> documents.replaceChunks(u, keepText ? chunks.getOrDefault(u, List.of())
                : List.of()))), Map.of());
        String container = (a.container() == null || a.container().isEmpty() ? "" : a.container() + "/")
            + (a.name() == null ? a.path() : a.name());
        Pipeline p = new Pipeline("scan-" + runId + "-" + a.path(), List.of(reader, sink));
        return new Runner(config.scan().memoryLimitMb() << 20, 4).run(p, e -> {
            if (stop.getAsBoolean()) {
                throw new Stopped();
            }
            if (e.kind() == MetadataEvent.Kind.DOCUMENT) {
                String parent = e.attributes().get(DocumentEvents.PARENT);
                String name = e.attributes().getOrDefault(DocumentEvents.NAME, "attachment");
                if (parent != null) {
                    // an attachment: an asset of its own, inside the message. One that cannot be
                    // recorded is noted on the message; the rest of the scan goes on.
                    try {
                        store.saveAsset(sourceId, runId, new Records.Asset(e.assetUrn(), KIND, container, name, null,
                            List.of()), List.of());
                        documents.setParent(e.assetUrn(), parent);
                        documents.saveDocument(document(e), runId);
                        read.add(e.assetUrn());
                    } catch (RuntimeException ex) {
                        LOG.warnf("Attachment %s of %s could not be recorded: %s", name, parent, ex.getMessage());
                        documents.addNote(parent, "An attachment, " + name + ", could not be recorded ("
                            + ex.getMessage() + ")");
                    }
                    return;
                }
                if ("true".equals(e.attributes().get(DocumentEvents.PASSING))) {
                    String note = JdbcDocumentStore.KEPT_NOTE + " (" + e.attributes().get(DocumentEvents.PROBLEM)
                        + "); what is shown is from an earlier scan";
                    if (documents.keepStored(e.assetUrn(), runId, note)) {
                        if (!keepText) {
                            documents.dropText(e.assetUrn());
                        }
                        return; // its text, sheets and attachments stay as they were
                    }
                }
                documents.saveDocument(document(e), runId);
                read.add(e.assetUrn());
            }
        });
    }

    /** The stored form of a {@code DOCUMENT} event. */
    DocumentRecords.Document document(MetadataEvent e) {
        Map<String, String> a = e.attributes();
        Map<String, String> mail = new LinkedHashMap<>();
        for (String k : List.of(Extraction.FROM, Extraction.TO, Extraction.CC, Extraction.SUBJECT, Extraction.DATE)) {
            if (a.get(k) != null) {
                mail.put(k, a.get(k));
            }
        }
        if (!isMail(a)) {
            mail.clear();
        }
        List<DocumentRecords.Page> pages = new ArrayList<>();
        for (Map<String, Object> p : parse(a.get(DocumentEvents.PAGE_LIST),
            new TypeReference<List<Map<String, Object>>>() { }, List.<Map<String, Object>>of())) {
            Object label = p.get("label");
            pages.add(new DocumentRecords.Page(((Number) p.get("number")).intValue(),
                ((Number) p.get("start")).intValue(), label == null ? null : label.toString()));
        }
        return new DocumentRecords.Document(e.assetUrn(), Boolean.parseBoolean(a.get(DocumentEvents.READABLE)),
            a.get(DocumentEvents.PROBLEM), a.get(DocumentEvents.FORMAT), a.get(DocumentEvents.CONTENT_TYPE),
            number(a.get(DocumentEvents.SIZE)), integer(a.get(Extraction.PAGES)), number(a.get(DocumentEvents.WORDS)),
            number(a.get(DocumentEvents.CHARACTERS)), a.get(DocumentEvents.LANGUAGE), a.get(Extraction.TITLE),
            a.get(Extraction.AUTHOR), instant(a.get(Extraction.CREATED)), instant(a.get(Extraction.MODIFIED)),
            a.get(DocumentEvents.OWNER),
            parse(a.get(DocumentEvents.TAGS), new TypeReference<Map<String, String>>() { }, Map.<String, String>of()),
            mail, pages,
            parse(a.get(DocumentEvents.NOTES), new TypeReference<List<String>>() { }, List.<String>of()),
            Boolean.parseBoolean(a.get(DocumentEvents.TRUNCATED)),
            !"false".equalsIgnoreCase(a.get(DocumentEvents.TEXT_KEPT)),
            integer(a.get(DocumentEvents.CHUNKS)) == null ? 0 : integer(a.get(DocumentEvents.CHUNKS)),
            e.observedAt());
    }

    private static boolean isMail(Map<String, String> a) {
        String t = a.get(DocumentEvents.CONTENT_TYPE);
        String f = a.get(DocumentEvents.FORMAT);
        return "message/rfc822".equals(t) || "application/vnd.ms-outlook".equals(t) || "eml".equals(f)
            || "msg".equals(f);
    }

    private <T> T parse(String text, TypeReference<T> type, T fallback) {
        if (text == null || text.isBlank()) {
            return fallback;
        }
        try {
            return json.readValue(text, type);
        } catch (JsonProcessingException e) {
            return fallback;
        }
    }

    private static Long number(String s) {
        try {
            return s == null ? null : Long.valueOf(s.strip());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Integer integer(String s) {
        Long n = number(s);
        return n == null || n > Integer.MAX_VALUE || n < 0 ? null : n.intValue();
    }

    private static Instant instant(String s) {
        try {
            return s == null ? null : Instant.parse(s);
        } catch (RuntimeException e) {
            return null;
        }
    }
}

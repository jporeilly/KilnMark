package io.kilnmark.server.pipelines;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

import org.jboss.logging.Logger;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import io.kilnmark.graph.store.GraphStore;
import io.kilnmark.graph.store.Records;
import io.kilnmark.kiln.MetadataEvent;
import io.kilnmark.kiln.connect.Connector;
import io.kilnmark.kiln.connect.SourceConfig;
import io.kilnmark.kiln.plugins.files.FilesConnector;
import io.kilnmark.server.audit.AuditLog;
import io.kilnmark.server.quality.Masking;
import io.kilnmark.server.quality.Sights;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

/**
 * The files a pipeline run wrote, handed back to the people who may have them (ADR 0055). A file
 * is named by its run and its place in the run's own record of what it wrote, never by a path the
 * caller gives; it is found where its write put it, in the source's folder on this server, and
 * only when it is still the file the run wrote (its place, size and time as the write recorded
 * them). Who may take it is the request's {@link Sights}: values for roles that may see data, and a
 * file holding a column marked sensitive, or whose columns are not known, only for a designer with
 * the unmask grant. Every download, and every refusal, is an audit event.
 */
@ApplicationScoped
public class WrittenFiles {

    private static final Logger LOG = Logger.getLogger(WrittenFiles.class);

    /** How far a file's time may stray from its run's, on another clock, when the run recorded none. */
    static final Duration SLACK = Duration.ofSeconds(5);

    static final String VIEWER = "Viewers do not see values from the data, so they cannot download files: a steward "
        + "or above can.";
    static final String SENSITIVE = "Holds sensitive columns: only people who may see sensitive values unmasked can "
        + "download it.";
    static final String UNKNOWN = "Its columns are not in the catalogue, so it is treated as sensitive: only people "
        + "who may see sensitive values unmasked can download it.";
    static final String MARKED_SINCE = "A column it was made from has been marked sensitive since the run, so it is "
        + "treated as sensitive: only people who may see sensitive values unmasked can download it.";
    static final String DELETED = "The file this run wrote has since been deleted. Run the pipeline again to write it "
        + "again.";
    static final String CHANGED = "The file there is no longer the one this run wrote: it was written again or "
        + "replaced since. Download it from the run that wrote it last, or run the pipeline again.";
    static final String NOT_THIS_RUN = "The file there was not written by this run: the run did not finish writing it, "
        + "or it was written again or replaced since. Download it from the run that wrote it last.";
    static final String UNREACHABLE = "The file cannot be reached where this run wrote it: the source's folder, or the "
        + "way to the file, has changed since. Ask an admin to check the source.";
    static final String SOURCE_GONE = "The source this run wrote to has been deleted, so its file cannot be found.";
    static final String NOT_HERE = "Only files written to a folder on this server can be downloaded; this one is "
        + "elsewhere (in a bucket, say). Read it from there.";
    static final String UNRECORDED = "This run did not record the file's size, so it may have changed since the run.";

    @Inject
    PipelineStore store;
    @Inject
    GraphStore graph;
    @Inject
    PipelineSources sources;
    @Inject
    AuditLog audit;
    @Inject
    ObjectMapper json;

    /**
     * One file a run wrote, as the caller may see it. {@code index} names it in the run's record;
     * {@code bytes} and {@code modified} are what the write recorded (null when it did not).
     * {@code state}: ready (there, as the run wrote it), unrecorded (there, but the run recorded no
     * size, so it may have changed), changed, deleted or unavailable. {@code reason} says why it
     * cannot be downloaded, {@code note} what to know when it can.
     */
    public record FileView(int index, String node, String source, String path, String format, Long rows, Long bytes,
                           Instant modified, String state, boolean downloadable, String reason, String note) {
    }

    /** {@code GET /api/v1/pipeline-runs/{id}/files}: {@code finished} false while the run goes (no files yet). */
    public record Listing(long run, boolean finished, List<FileView> files) {
    }

    /** A file cleared for sending: where it is, as checked, and how to name it. */
    public record Download(FilesConnector.LocalFile file, String filename, String contentType) {
    }

    /** One entry of the run's record of what it wrote, with its place there. */
    record Written(int index, String node, String urn, String source, String target, boolean registered, Long rows,
                   String location, Long bytes, Instant modified) {
    }

    /** What checking a file gave: its state, the file when it can be sent, and the status and words when not. */
    record Checked(String state, FilesConnector.LocalFile file, Response.Status status, String reason, String code) {
        boolean usable() {
            return file != null;
        }
    }

    /**
     * What a write reported about the file it put in a folder on this server ({@code SCHEMA}'s
     * {@code file}, {@code bytes} and {@code modified}, docs/design/pipelines.md section 5), for the
     * run's record; null for a bucket or a table, or when it reported none.
     */
    static ObjectNode recorded(List<MetadataEvent> events, String urn, String target, ObjectMapper json) {
        for (MetadataEvent e : events) {
            if (e.kind() != MetadataEvent.Kind.SCHEMA || !e.assetUrn().equals(urn)
                || !Objects.equals(target, e.attributes().get("target"))) {
                continue;
            }
            Map<String, String> a = e.attributes();
            String file = a.get(FilesConnector.WRITTEN_FILE);
            String bytes = a.get(FilesConnector.WRITTEN_BYTES);
            String modified = a.get(FilesConnector.WRITTEN_MODIFIED);
            if (file == null || bytes == null || modified == null) {
                return null;
            }
            try {
                ObjectNode n = json.createObjectNode();
                n.put("location", file);
                n.put("bytes", Long.parseLong(bytes));
                n.put("modified", Instant.parse(modified).toString());
                return n;
            } catch (NumberFormatException | DateTimeParseException ex) {
                return null;
            }
        }
        return null;
    }

    /** The files a run wrote; 404 when there is no such run. */
    public Listing list(long id, Sights sights) {
        PipelineStore.Run r = run(id);
        if (!r.finished()) {
            return new Listing(id, false, List.of());
        }
        List<FileView> out = new ArrayList<>();
        for (Written w : written(r)) {
            Checked c = check(r, w);
            String refused = c.usable() ? refusal(r, w, sights) : null;
            String reason = !c.usable() ? c.reason() : refused;
            out.add(new FileView(w.index(), w.node(), w.source(), w.target(), format(w.target()), w.rows(), w.bytes(),
                w.modified(), c.state(), c.usable() && refused == null, reason,
                c.usable() && c.state().equals("unrecorded") ? UNRECORDED : null));
        }
        return new Listing(id, true, out);
    }

    /**
     * Checks one file of a run for sending to the caller, recording the download or its refusal in
     * the audit log. Refusals are thrown with their status: 404 no such run or file, 409 the run has
     * not finished or the file changed or cannot be reached, 410 deleted, 403 not for this caller.
     */
    public Download download(long id, String index, Sights sights, AuditLog.Actor actor) {
        String target = "pipeline-run/" + id;
        PipelineStore.Run r = store.run(id).orElse(null);
        if (r == null) {
            throw refuse(actor, target, null, null, "no_run", Response.Status.NOT_FOUND, "No pipeline run " + id);
        }
        if (!r.finished()) {
            throw refuse(actor, target, null, null, "running", Response.Status.CONFLICT,
                "The run has not finished: its files can be downloaded once it has.");
        }
        Written w = null;
        try {
            int i = Integer.parseInt(index);
            w = written(r).stream().filter(x -> x.index() == i).findFirst().orElse(null);
        } catch (NumberFormatException e) {
            // no such file, below
        }
        if (w == null) {
            throw refuse(actor, target, null, null, "no_file", Response.Status.NOT_FOUND,
                "Run " + id + " wrote no such file. List what it wrote with GET .../files.");
        }
        Checked c = check(r, w);
        if (!c.usable()) {
            throw refuse(actor, target, w, null, c.code(), c.status(), c.reason());
        }
        String refused = refusal(r, w, sights);
        if (refused != null) {
            throw refuse(actor, target, w, c.file().size(), refused.equals(VIEWER) ? "no_values" : "sensitive",
                Response.Status.FORBIDDEN, refused);
        }
        Map<String, Object> detail = detail(w, c.file().size());
        if (c.state().equals("unrecorded")) {
            detail.put("unrecorded", true);
        }
        audit.record(actor, AuditLog.FILE_DOWNLOADED, target, detail);
        return new Download(c.file(), filename(w.target()), contentType(w.target()));
    }

    private PipelineStore.Run run(long id) {
        return store.run(id).orElseThrow(() -> PipelineService.fail(Response.Status.NOT_FOUND, "No pipeline run " + id));
    }

    /** The run's record of what it wrote: the writes to files, by their place in it. */
    List<Written> written(PipelineStore.Run r) {
        List<Written> out = new ArrayList<>();
        JsonNode result;
        try {
            result = r.result() == null ? null : json.readTree(r.result());
        } catch (IOException e) {
            return out;
        }
        if (result == null) {
            return out;
        }
        int i = 0;
        for (JsonNode n : result.path("written")) {
            int index = i++;
            JsonNode file = n.path("file");
            String source = n.path("source").asText();
            boolean files = file.isObject() || graph.source(source).flatMap(s -> sources.connector(s.kind()))
                .map(k -> "files".equals(k.category())).orElse(false);
            if (!files) {
                continue; // a table
            }
            Instant modified = null;
            try {
                modified = file.hasNonNull("modified") ? Instant.parse(file.get("modified").asText()) : null;
            } catch (DateTimeParseException e) {
                // not recorded, then
            }
            out.add(new Written(index, n.path("node").asText(), n.path("urn").asText(), source,
                n.path("target").asText(), n.path("registered").asBoolean(),
                n.hasNonNull("rows") ? n.get("rows").asLong() : null,
                file.hasNonNull("location") ? file.get("location").asText() : null,
                file.hasNonNull("bytes") ? file.get("bytes").asLong() : null, modified));
        }
        return out;
    }

    /**
     * Finds the file as its write did, in the source's folder as it is now, and compares it with what
     * the run recorded: its place (links resolved), size and time. With nothing recorded (a run from
     * before files were recorded) the file must be in a folder the server may write, and changed
     * within the run's own time.
     */
    Checked check(PipelineStore.Run r, Written w) {
        Records.Source s = graph.source(w.source()).orElse(null);
        if (s == null) {
            return new Checked("unavailable", null, Response.Status.GONE, SOURCE_GONE, "source_deleted");
        }
        Connector connector = sources.connector(s.kind()).orElse(null);
        if (!(connector instanceof FilesConnector folder)) {
            return new Checked("unavailable", null, Response.Status.CONFLICT, NOT_HERE, "not_here");
        }
        FilesConnector.LocalFile now;
        try {
            now = folder.writtenFile(new SourceConfig(s.name(), s.kind(), s.settings(), null), w.target());
        } catch (NoSuchFileException e) {
            return new Checked("deleted", null, Response.Status.GONE, DELETED, "deleted");
        } catch (IOException | RuntimeException e) {
            LOG.debugf("Run %d's file from node %s cannot be reached: %s", r.id(), w.node(), e.getMessage());
            return new Checked("unavailable", null, Response.Status.CONFLICT, UNREACHABLE, "unreachable");
        }
        if (w.bytes() != null && w.modified() != null && w.location() != null) {
            if (!now.path().toString().equals(w.location()) || now.size() != w.bytes()
                || !now.modified().equals(w.modified())) {
                return new Checked("changed", null, Response.Status.CONFLICT, CHANGED, "changed");
            }
            return new Checked("ready", now, Response.Status.OK, null, null);
        }
        Instant from = (r.startedAt() == null ? r.requestedAt() : r.startedAt()).minus(SLACK);
        Instant to = (r.finishedAt() == null ? Instant.now() : r.finishedAt()).plus(SLACK);
        if (now.modified().isBefore(from) || now.modified().isAfter(to) || !Files.isWritable(now.path().getParent())) {
            return new Checked("changed", null, Response.Status.CONFLICT, NOT_THIS_RUN, "changed");
        }
        return new Checked("unrecorded", now, Response.Status.OK, null, null);
    }

    /** Why the caller may not take the file, in words, or null when they may: decided by {@link Sights}. */
    String refusal(PipelineStore.Run r, Written w, Sights sights) {
        boolean known = w.registered() && graph.asset(w.urn()).map(a -> !a.fields().isEmpty() && a.removedAt() == null)
            .orElse(false);
        List<String> from = store.lineageOfRun(r.id()).stream()
            .filter(l -> l.nodeId().equals(w.node()) && l.outputUrn().equals(w.urn()))
            .map(PipelineStore.Lineage::inputUrn).distinct().toList();
        Masking.Sight sight = sights.wholeFile(w.urn(), known, from,
            r.startedAt() == null ? r.requestedAt() : r.startedAt());
        if (!sight.anyHidden()) {
            return null;
        }
        if (!sight.data()) {
            return VIEWER;
        }
        if (!sights.marks(w.urn()).isEmpty()) {
            return SENSITIVE;
        }
        return known ? MARKED_SINCE : UNKNOWN;
    }

    private WebApplicationException refuse(AuditLog.Actor actor, String target, Written w, Long bytes, String code,
                                           Response.Status status, String message) {
        Map<String, Object> detail = w == null ? new java.util.LinkedHashMap<>() : detail(w, bytes);
        detail.put("refused", code);
        detail.put("status", status.getStatusCode());
        audit.record(actor, AuditLog.FILE_DOWNLOAD_REFUSED, target, detail);
        return new WebApplicationException(Response.status(status).type(MediaType.APPLICATION_JSON)
            .entity(Map.of("error", message)).build());
    }

    private static Map<String, Object> detail(Written w, Long bytes) {
        Map<String, Object> d = new java.util.LinkedHashMap<>();
        d.put("node", w.node());
        d.put("source", w.source());
        d.put("path", w.target());
        if (bytes != null) {
            d.put("bytes", bytes);
        }
        return d;
    }

    /** csv or parquet, from the file's extension (writes are checked to match their format). */
    static String format(String path) {
        int dot = path.lastIndexOf('.');
        return dot < 0 ? "" : path.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    /** The response's type, from the extension: never one a browser would run or show as a page. */
    static String contentType(String path) {
        return switch (format(path)) {
            case "csv" -> "text/csv; charset=utf-8";
            case "parquet" -> "application/vnd.apache.parquet";
            case "json" -> "application/json";
            default -> "application/octet-stream";
        };
    }

    /**
     * The name the browser saves it as: the file's own name, without its folders, kept to letters,
     * digits, dots, dashes and underscores, never starting with a dot, and at most 120 characters
     * (its extension kept).
     */
    static String filename(String path) {
        String base = path.substring(path.lastIndexOf('/') + 1);
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < base.length(); i++) {
            char c = base.charAt(i);
            b.append(c < 0x80 && (Character.isLetterOrDigit(c) || c == '.' || c == '-' || c == '_') ? c : '_');
        }
        while (b.length() > 0 && b.charAt(0) == '.') {
            b.setCharAt(0, '_');
        }
        String name = b.toString();
        if (name.length() > 120) {
            String ext = format(name);
            name = name.substring(0, 119 - ext.length()) + "." + ext;
        }
        return name.isEmpty() || name.chars().allMatch(c -> c == '_') ? "download" : name;
    }

    /**
     * Sends the file as it was checked, a buffer at a time, never all of it at once: refused when,
     * as it is opened, it is no longer that file (another size, time or file), or is a link; and
     * exactly its checked size is sent, so a file growing meanwhile sends no more.
     */
    public static void copy(FilesConnector.LocalFile expected, OutputStream out) throws IOException {
        try (FileChannel channel = FileChannel.open(expected.path(), StandardOpenOption.READ,
            LinkOption.NOFOLLOW_LINKS)) {
            BasicFileAttributes a = Files.readAttributes(expected.path(), BasicFileAttributes.class,
                LinkOption.NOFOLLOW_LINKS);
            if (!a.isRegularFile() || a.size() != expected.size() || channel.size() != expected.size()
                || !a.lastModifiedTime().toInstant().equals(expected.modified())
                || !Objects.equals(a.fileKey(), expected.key())) {
                throw new IOException("The file changed as it was about to be sent");
            }
            byte[] chunk = new byte[1 << 16];
            ByteBuffer buffer = ByteBuffer.wrap(chunk);
            long left = expected.size();
            while (left > 0) {
                buffer.clear();
                if (left < chunk.length) {
                    buffer.limit((int) left);
                }
                int n = channel.read(buffer);
                if (n < 0) {
                    throw new IOException("The file became shorter as it was sent");
                }
                out.write(chunk, 0, n);
                left -= n;
            }
            out.flush();
        }
    }
}

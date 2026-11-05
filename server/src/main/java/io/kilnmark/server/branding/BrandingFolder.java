package io.kilnmark.server.branding;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.kilnmark.server.KilnmarkConfig;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

/**
 * The operator's branding folder (docs/guide/branding.md): {@code manifest.json} plus the images it
 * names. Kilnmark ships no vendor logos; this lets a customer use logos they are licensed to use.
 *
 * <p>The folder is untrusted input. Only files the manifest names are ever read or served; names
 * are plain file names (no folders, no {@code ..}); symbolic links are not followed; only SVG, PNG
 * and WebP whose content matches the extension are accepted, each up to a size cap. Anything
 * invalid is left out with a warning in the log, and the UI falls back to its letter tiles.
 *
 * <p>What stops an SVG running script is how it is served and shown: a sandboxing
 * Content-Security-Policy and {@code nosniff} on the response ({@link BrandingResource}), and the
 * UI showing it only through {@code <img>}. Refusing SVGs with a DOCTYPE, entities, scripts, event
 * attributes, {@code javascript:} links or {@code foreignObject} is defence in depth on top.
 *
 * <p>The endpoints are public, so the work per request is bounded: the folder's size and
 * modification stamps are rechecked at most once per {@link #RECHECK_NANOS}, a reload reads only
 * the manifest and the files of valid entries, and at most {@link #MAX_ENTRIES} entries are looked
 * at. Each stamp is taken before its file is read, so a change during a reload is seen at the next
 * check rather than lost.
 */
@ApplicationScoped
public class BrandingFolder {

    private static final Logger LOG = Logger.getLogger(BrandingFolder.class);

    /** The manifest's own size cap. */
    static final int MAX_MANIFEST_BYTES = 64 * 1024;
    /** Connector entries looked at in one manifest, valid or not; the rest are ignored. */
    static final int MAX_ENTRIES = 200;
    /** All images held in memory together, and so the largest one image can be. */
    static final long MAX_TOTAL_BYTES = 8L * 1024 * 1024;
    /** Longest product name or label kept. */
    static final int MAX_TEXT = 60;
    /** How often the folder is checked for changes, at most. */
    static final long RECHECK_NANOS = TimeUnit.MILLISECONDS.toNanos(1500);

    private static final Pattern FILE = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_-]*(\\.[A-Za-z0-9_-]+)*\\.(svg|png|webp)",
        Pattern.CASE_INSENSITIVE);
    private static final Pattern CONNECTOR_ID = Pattern.compile("[a-z0-9][a-z0-9-]{0,63}");
    private static final Pattern COLOUR = Pattern.compile("#[0-9A-Fa-f]{6}");
    private static final Pattern SVG_REFUSED = Pattern.compile(
        "<!DOCTYPE|<!ENTITY|<script|<foreignObject|javascript:|\\son[a-z]+\\s*=", Pattern.CASE_INSENSITIVE);
    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'};

    /** What the UI reads: links to images, never their content. */
    public record View(String productName, String logo, Map<String, ConnectorLook> connectors) {
        static final View EMPTY = new View(null, null, Map.of());
    }

    /** One connector's look: an image link, a brand colour (#rrggbb) and a label, each optional. */
    public record ConnectorLook(String image, String color, String label) {
    }

    /** An image ready to serve. {@code etag} is a hash of its bytes. */
    public record Image(String name, String contentType, byte[] bytes, String etag) {
    }

    /**
     * What was loaded. {@code stamps} holds the manifest's and every named file's stamp as taken
     * before reading it (valid or not, so adding or fixing a file is noticed).
     */
    private record State(Map<String, String> stamps, View view, Map<String, Image> images, long checkedAt) {
    }

    private static final String MANIFEST = "manifest.json";

    private final Path dir;
    private final long maxFileBytes;
    private final long recheckNanos;
    private final ObjectMapper json = new ObjectMapper();
    private volatile State state;

    @Inject
    public BrandingFolder(KilnmarkConfig config) {
        this(Path.of(config.branding().dir()), config.branding().maxFileKb() * 1024L, RECHECK_NANOS);
    }

    /**
     * For tests: a folder, the largest image it may serve in bytes (clamped to 1 byte ..
     * {@link #MAX_TOTAL_BYTES}), and how often to look for changes (0: on every call).
     */
    BrandingFolder(Path dir, long maxFileBytes, long recheckNanos) {
        this.dir = dir.toAbsolutePath().normalize();
        this.maxFileBytes = Math.clamp(maxFileBytes, 1, MAX_TOTAL_BYTES);
        this.recheckNanos = recheckNanos;
    }

    /** The current branding: product name, logo and connector looks, with links to the images. */
    public View view() {
        return current().view();
    }

    /** An image the manifest names, if it is valid; nothing for any other name. */
    public Optional<Image> image(String name) {
        return Optional.ofNullable(current().images().get(name));
    }

    /** The URL the UI uses for an image; the hash changes with the content, so caches stay correct. */
    static String link(Image i) {
        return "/api/v1/branding/files/" + i.name() + "?v=" + i.etag();
    }

    private State current() {
        State s = state;
        long now = System.nanoTime();
        if (s != null && now - s.checkedAt() < recheckNanos) {
            return s;
        }
        synchronized (this) {
            s = state;
            now = System.nanoTime();
            if (s == null || (now - s.checkedAt() >= recheckNanos && changed(s))) {
                s = load();
            } else if (now - s.checkedAt() >= recheckNanos) {
                s = new State(s.stamps(), s.view(), s.images(), now);
            }
            state = s;
            return s;
        }
    }

    private boolean changed(State s) {
        for (Map.Entry<String, String> e : s.stamps().entrySet()) {
            if (!stamp(dir.resolve(e.getKey())).equals(e.getValue())) {
                return true;
            }
        }
        return false;
    }

    private static String stamp(Path p) {
        try {
            BasicFileAttributes a = Files.readAttributes(p, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            return a.size() + ":" + a.lastModifiedTime().toMillis() + ":" + a.isRegularFile() + ":" + a.fileKey();
        } catch (IOException e) {
            return "none";
        }
    }

    /** One reload: what has been stamped, read and found wrong so far. */
    private static final class Load {
        final Map<String, String> stamps = new LinkedHashMap<>();
        final Map<String, Image> images = new LinkedHashMap<>();
        final List<String> problems = new ArrayList<>();
        long total;
    }

    private State load() {
        Load l = new Load();
        Path manifest = dir.resolve(MANIFEST);
        // Stamp first, then read: a change after the stamp makes the next check reload.
        l.stamps.put(MANIFEST, stamp(manifest));
        View view = View.EMPTY;
        if (Files.isRegularFile(manifest, LinkOption.NOFOLLOW_LINKS)) {
            try {
                view = parse(readCapped(manifest, MAX_MANIFEST_BYTES), l);
            } catch (IOException | RuntimeException e) {
                l.problems.add(MANIFEST + ": " + e.getMessage());
            }
        } else if (Files.exists(manifest, LinkOption.NOFOLLOW_LINKS)) {
            l.problems.add(MANIFEST + ": not a regular file (symbolic links are not followed)");
        }
        for (String p : l.problems) {
            LOG.warnf("Branding folder %s: %s", dir, p);
        }
        return new State(Map.copyOf(l.stamps), view, Map.copyOf(l.images), System.nanoTime());
    }

    private View parse(byte[] bytes, Load l) throws IOException {
        JsonNode root = json.readTree(bytes);
        if (root == null || !root.isObject()) {
            throw new IllegalArgumentException("must be a JSON object");
        }
        String productName = null;
        String logo = null;
        JsonNode product = root.path("product");
        if (product.isObject()) {
            productName = text(product.path("name"), "product.name", l.problems);
            Image i = image(product.path("logo"), "product.logo", l);
            logo = i == null ? null : link(i);
        } else if (!product.isMissingNode()) {
            l.problems.add("product: must be an object");
        }
        Map<String, ConnectorLook> looks = new LinkedHashMap<>();
        JsonNode connectors = root.path("connectors");
        if (connectors.isObject()) {
            int seen = 0;
            for (Map.Entry<String, JsonNode> e : connectors.properties()) {
                if (++seen > MAX_ENTRIES) {
                    l.problems.add("connectors: more than " + MAX_ENTRIES + " entries; the rest are ignored");
                    break;
                }
                String id = e.getKey();
                String where = "connectors." + id;
                JsonNode c = e.getValue();
                if (!CONNECTOR_ID.matcher(id).matches()) {
                    l.problems.add(where + ": not a connector id (lower-case letters, digits and hyphens)");
                    continue;
                }
                if (!c.isObject()) {
                    l.problems.add(where + ": must be an object");
                    continue;
                }
                // Everything that needs no disk is checked before the file is touched.
                String colour = colour(c.path("color"), where + ".color", l.problems);
                String label = text(c.path("label"), where + ".label", l.problems);
                Image i = image(c.path("file"), where + ".file", l);
                if (i != null || colour != null || label != null) {
                    looks.put(id, new ConnectorLook(i == null ? null : link(i), colour, label));
                }
            }
        } else if (!connectors.isMissingNode()) {
            l.problems.add("connectors: must be an object");
        }
        return new View(productName, logo, Map.copyOf(looks));
    }

    private Image image(JsonNode node, String where, Load l) {
        if (node.isMissingNode() || node.isNull()) {
            return null;
        }
        String name = node.isTextual() ? node.asText() : "";
        if (name.length() > 100 || !FILE.matcher(name).matches()) {
            l.problems.add(where + ": a plain file name ending .svg, .png or .webp, without folders");
            return null;
        }
        Image known = l.images.get(name);
        if (known != null) {
            return known;
        }
        if (l.stamps.containsKey(name)) {
            return null; // named before and refused then: already reported
        }
        Path p = dir.resolve(name).normalize();
        if (!dir.equals(p.getParent())) {
            l.problems.add(where + ": outside the branding folder");
            return null;
        }
        l.stamps.put(name, stamp(p));
        try {
            if (!Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS)) {
                l.problems.add(where + ": " + name + " is missing or not a regular file (symbolic links are not followed)");
                return null;
            }
            if (Files.size(p) > maxFileBytes) {
                l.problems.add(where + ": " + name + " is larger than " + maxFileBytes / 1024 + " KB");
                return null;
            }
            if (l.total + Files.size(p) > MAX_TOTAL_BYTES) {
                l.problems.add(where + ": images together exceed " + MAX_TOTAL_BYTES / (1024 * 1024) + " MB");
                return null;
            }
            byte[] bytes = readCapped(p, maxFileBytes);
            String type = contentType(name, bytes);
            if (type == null) {
                l.problems.add(where + ": " + name + " is not a valid " + extension(name).toUpperCase(Locale.ROOT)
                    + " image, or is an SVG holding a DOCTYPE, entities, scripts or event attributes");
                return null;
            }
            l.total += bytes.length;
            Image i = new Image(name, type, bytes, hash(bytes));
            l.images.put(name, i);
            return i;
        } catch (IOException e) {
            l.problems.add(where + ": " + name + " cannot be read (" + e.getMessage() + ")");
            return null;
        }
    }

    /** The content type for a file whose bytes match its extension; null when they do not. */
    static String contentType(String name, byte[] b) {
        return switch (extension(name)) {
            case "png" -> startsWith(b, PNG) ? "image/png" : null;
            case "webp" -> b.length >= 12 && b[0] == 'R' && b[1] == 'I' && b[2] == 'F' && b[3] == 'F'
                && b[8] == 'W' && b[9] == 'E' && b[10] == 'B' && b[11] == 'P' ? "image/webp" : null;
            case "svg" -> validSvg(b) ? "image/svg+xml" : null;
            default -> null;
        };
    }

    private static boolean validSvg(byte[] b) {
        String text = new String(b, StandardCharsets.UTF_8);
        if (text.startsWith("﻿")) {
            text = text.substring(1);
        }
        String head = text.stripLeading();
        boolean xml = head.startsWith("<?xml") || head.startsWith("<svg") || head.startsWith("<!--");
        return xml && text.contains("<svg") && !SVG_REFUSED.matcher(text).find();
    }

    private static String extension(String name) {
        return name.substring(name.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
    }

    private static boolean startsWith(byte[] b, byte[] prefix) {
        if (b.length < prefix.length) {
            return false;
        }
        for (int i = 0; i < prefix.length; i++) {
            if (b[i] != prefix[i]) {
                return false;
            }
        }
        return true;
    }

    private static String text(JsonNode node, String where, List<String> problems) {
        if (node.isMissingNode() || node.isNull()) {
            return null;
        }
        if (!node.isTextual()) {
            problems.add(where + ": must be text");
            return null;
        }
        String t = node.asText().replaceAll("\\p{Cntrl}", "").strip();
        if (t.length() > MAX_TEXT) {
            problems.add(where + ": longer than " + MAX_TEXT + " characters; shortened");
            t = t.substring(0, MAX_TEXT).strip();
        }
        return t.isEmpty() ? null : t;
    }

    private static String colour(JsonNode node, String where, List<String> problems) {
        if (node.isMissingNode() || node.isNull()) {
            return null;
        }
        if (!node.isTextual() || !COLOUR.matcher(node.asText()).matches()) {
            problems.add(where + ": a colour as #rrggbb");
            return null;
        }
        return node.asText().toLowerCase(Locale.ROOT);
    }

    /** Reads at most {@code cap} bytes (cap at most 8 MB); a longer file (grown since it was measured) is refused. */
    private static byte[] readCapped(Path p, long cap) throws IOException {
        try (InputStream in = Files.newInputStream(p, LinkOption.NOFOLLOW_LINKS)) {
            byte[] bytes = in.readNBytes((int) Math.min(cap, MAX_TOTAL_BYTES) + 1);
            if (bytes.length > cap) {
                throw new IOException("larger than " + cap / 1024 + " KB");
            }
            return bytes;
        }
    }

    private static String hash(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes), 0, 8);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}

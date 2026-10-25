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
 * and WebP whose content matches the extension are accepted, each up to a size cap; SVGs with a
 * DOCTYPE, entities or scripts are refused. Anything invalid is left out with a warning in the log,
 * and the UI falls back to its monogram tiles.
 *
 * <p>The folder is read again whenever the manifest or a file it names changes (checked by size
 * and modification time on each request), so an operator can change it without a restart.
 */
@ApplicationScoped
public class BrandingFolder {

    private static final Logger LOG = Logger.getLogger(BrandingFolder.class);

    /** The manifest's own size cap. */
    static final int MAX_MANIFEST_BYTES = 64 * 1024;
    /** Connector entries read from one manifest; the rest are ignored. */
    static final int MAX_CONNECTORS = 200;
    /** All images held in memory together. */
    static final long MAX_TOTAL_BYTES = 8L * 1024 * 1024;
    /** Longest product name or label kept. */
    static final int MAX_TEXT = 60;

    private static final Pattern FILE = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_-]*(\\.[A-Za-z0-9_-]+)*\\.(svg|png|webp)",
        Pattern.CASE_INSENSITIVE);
    private static final Pattern CONNECTOR_ID = Pattern.compile("[a-z0-9][a-z0-9-]{0,63}");
    private static final Pattern COLOUR = Pattern.compile("#[0-9A-Fa-f]{6}");
    private static final Pattern SVG_REFUSED = Pattern.compile("<!DOCTYPE|<!ENTITY|<script", Pattern.CASE_INSENSITIVE);
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

    /** What was loaded, and the file names watched for changes (every name the manifest gave). */
    private record State(String fingerprint, View view, Map<String, Image> images, List<String> watched) {
    }

    private final Path dir;
    private final long maxFileBytes;
    private final ObjectMapper json = new ObjectMapper();
    private volatile State state = new State("", View.EMPTY, Map.of(), List.of());

    @Inject
    public BrandingFolder(KilnmarkConfig config) {
        this(Path.of(config.branding().dir()), config.branding().maxFileKb() * 1024L);
    }

    /** For tests: a folder and the largest image it may serve, in bytes. */
    public BrandingFolder(Path dir, long maxFileBytes) {
        this.dir = dir.toAbsolutePath().normalize();
        this.maxFileBytes = maxFileBytes;
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
        String now = fingerprint(s.watched());
        if (!s.fingerprint().equals(now)) {
            synchronized (this) {
                s = state;
                if (!s.fingerprint().equals(fingerprint(s.watched()))) {
                    s = load();
                    state = s;
                }
            }
        }
        return s;
    }

    /**
     * Sizes and modification times of the manifest and every file it named at the last load, valid
     * or not (so adding or fixing a file is noticed): cheap enough to check on each request.
     */
    private String fingerprint(List<String> names) {
        StringBuilder b = new StringBuilder(stamp(dir.resolve("manifest.json")));
        for (String name : names) {
            b.append('|').append(name).append(stamp(dir.resolve(name)));
        }
        return b.toString();
    }

    private static String stamp(Path p) {
        try {
            BasicFileAttributes a = Files.readAttributes(p, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            return ":" + a.size() + ":" + a.lastModifiedTime().toMillis() + ":" + a.isRegularFile();
        } catch (IOException e) {
            return ":none";
        }
    }

    private State load() {
        Path manifest = dir.resolve("manifest.json");
        List<String> problems = new ArrayList<>();
        List<String> watched = new ArrayList<>();
        Map<String, Image> images = new LinkedHashMap<>();
        View view = View.EMPTY;
        if (Files.isRegularFile(manifest, LinkOption.NOFOLLOW_LINKS)) {
            try {
                view = parse(readCapped(manifest, MAX_MANIFEST_BYTES), images, problems, watched);
            } catch (IOException | RuntimeException e) {
                problems.add("manifest.json: " + e.getMessage());
            }
        } else if (Files.exists(manifest, LinkOption.NOFOLLOW_LINKS)) {
            problems.add("manifest.json: not a regular file (symbolic links are not followed)");
        }
        for (String p : problems) {
            LOG.warnf("Branding folder %s: %s", dir, p);
        }
        List<String> names = List.copyOf(watched);
        return new State(fingerprint(names), view, Map.copyOf(images), names);
    }

    private View parse(byte[] bytes, Map<String, Image> images, List<String> problems, List<String> watched)
        throws IOException {
        JsonNode root = json.readTree(bytes);
        if (root == null || !root.isObject()) {
            throw new IllegalArgumentException("must be a JSON object");
        }
        long[] total = {0};
        String productName = null;
        String logo = null;
        JsonNode product = root.path("product");
        if (product.isObject()) {
            productName = text(product.path("name"), "product.name", problems);
            Image i = image(product.path("logo"), "product.logo", images, problems, watched, total);
            logo = i == null ? null : link(i);
        } else if (!product.isMissingNode()) {
            problems.add("product: must be an object");
        }
        Map<String, ConnectorLook> looks = new LinkedHashMap<>();
        JsonNode connectors = root.path("connectors");
        if (connectors.isObject()) {
            for (Map.Entry<String, JsonNode> e : connectors.properties()) {
                String id = e.getKey();
                String where = "connectors." + id;
                if (looks.size() >= MAX_CONNECTORS) {
                    problems.add("connectors: more than " + MAX_CONNECTORS + " entries; the rest are ignored");
                    break;
                }
                if (!CONNECTOR_ID.matcher(id).matches()) {
                    problems.add(where + ": not a connector id (lower-case letters, digits and hyphens)");
                    continue;
                }
                if (!e.getValue().isObject()) {
                    problems.add(where + ": must be an object");
                    continue;
                }
                JsonNode c = e.getValue();
                Image i = image(c.path("file"), where + ".file", images, problems, watched, total);
                String colour = colour(c.path("color"), where + ".color", problems);
                String label = text(c.path("label"), where + ".label", problems);
                if (i != null || colour != null || label != null) {
                    looks.put(id, new ConnectorLook(i == null ? null : link(i), colour, label));
                }
            }
        } else if (!connectors.isMissingNode()) {
            problems.add("connectors: must be an object");
        }
        return new View(productName, logo, Map.copyOf(looks));
    }

    private Image image(JsonNode node, String where, Map<String, Image> images, List<String> problems,
                        List<String> watched, long[] total) {
        if (node.isMissingNode() || node.isNull()) {
            return null;
        }
        String name = node.isTextual() ? node.asText() : "";
        if (!FILE.matcher(name).matches() || name.length() > 100) {
            problems.add(where + ": a plain file name ending .svg, .png or .webp, without folders");
            return null;
        }
        Image known = images.get(name);
        if (known != null) {
            return known;
        }
        if (!watched.contains(name)) {
            watched.add(name);
        }
        Path p = dir.resolve(name).normalize();
        if (!dir.equals(p.getParent())) {
            problems.add(where + ": outside the branding folder");
            return null;
        }
        try {
            if (!Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS)) {
                problems.add(where + ": " + name + " is missing or not a regular file (symbolic links are not followed)");
                return null;
            }
            if (Files.size(p) > maxFileBytes) {
                problems.add(where + ": " + name + " is larger than " + maxFileBytes / 1024 + " KB");
                return null;
            }
            byte[] bytes = readCapped(p, maxFileBytes);
            String type = contentType(name, bytes);
            if (type == null) {
                problems.add(where + ": " + name + " is not a valid " + extension(name).toUpperCase(Locale.ROOT)
                    + " image, or holds a DOCTYPE, entities or a script");
                return null;
            }
            if (total[0] + bytes.length > MAX_TOTAL_BYTES) {
                problems.add(where + ": images together exceed " + MAX_TOTAL_BYTES / (1024 * 1024) + " MB");
                return null;
            }
            total[0] += bytes.length;
            Image i = new Image(name, type, bytes, hash(bytes));
            images.put(name, i);
            return i;
        } catch (IOException e) {
            problems.add(where + ": " + name + " cannot be read (" + e.getMessage() + ")");
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

    /** Reads at most {@code cap} bytes; a longer file (grown since it was measured) is refused. */
    private static byte[] readCapped(Path p, long cap) throws IOException {
        try (InputStream in = Files.newInputStream(p, LinkOption.NOFOLLOW_LINKS)) {
            byte[] bytes = in.readNBytes((int) cap + 1);
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

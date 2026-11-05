package io.kilnmark.server.ai.assistant;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Splits a page of documentation into sections at its {@code ##} and {@code ###} headings, with
 * the same anchors the in-app viewer gives them ({@link DocSlugs}): headings are found as the
 * viewer's Markdown reader finds them (# headings with up to three spaces before them, never
 * inside fenced code). Deeper headings stay inside their section but still take their anchor, so
 * later suffixes (-1, -2) agree with the viewer. Underlined (setext) headings are not used in the
 * guides and are read as text.
 *
 * <p>The text before the first {@code ##} heading is the page's own section, addressed by the page
 * id alone.
 */
public final class DocSections {

    /** The separator in title paths, as people see it: "Setting data quality rules › Thresholds". */
    public static final String PATH_SEPARATOR = " › ";

    private static final Pattern TITLE = Pattern.compile("(?m)^#\\s+(.+)$");
    private static final Pattern HEADING = Pattern.compile("^ {0,3}(#{1,6})(?=[ \\t]|$)(.*)$");
    private static final Pattern FENCE_OPEN = Pattern.compile("^ {0,3}(`{3,}|~{3,})(.*)$");
    private static final Pattern TABLE_RULE = Pattern.compile("^\\s*\\|?\\s*:?-{3,}:?\\s*(\\|\\s*:?-{3,}:?\\s*)*\\|?\\s*$");
    private static final Pattern IMAGE_OR_LINK = Pattern.compile("!?\\[([^\\]]*)\\]\\([^)]*\\)");
    private static final Pattern STRONG = Pattern.compile("\\*\\*|__");
    private static final Pattern EMPHASIS = Pattern.compile("(?<![\\w*])\\*(?=\\S)([^*\\n]+?)(?<=\\S)\\*(?![\\w*])");
    private static final Pattern QUOTE = Pattern.compile("^ {0,3}> ?");

    private DocSections() {
    }

    /** A page: its id (the file name without .md, or whats-new), title and Markdown. */
    public record Page(String id, String title, String markdown) {
    }

    /**
     * One section. {@code slug} is null for the page's own section (the text before its first
     * section heading); {@code id} is {@code page/slug}, or the page id alone for that one.
     */
    public record Section(String page, String slug, String id, int depth, String titlePath, String body,
                          int position) {

        /** SHA-256 of the title path and text: a section is indexed again only when this changes. */
        public byte[] hash() {
            try {
                return MessageDigest.getInstance("SHA-256")
                    .digest((titlePath + "\n" + body).getBytes(StandardCharsets.UTF_8));
            } catch (NoSuchAlgorithmException e) {
                throw new IllegalStateException(e);
            }
        }

        /** The link the in-app viewer opens at this section, as the UI's docHref makes it. */
        public String href() {
            return DocSections.href(page, slug);
        }
    }

    /** The viewer's link to a page or one of its sections: {@code #/docs/<page>/<slug>}, each part URI-encoded. */
    public static String href(String page, String slug) {
        return "#/docs/" + encode(page) + (slug == null || slug.isEmpty() ? "" : "/" + encode(slug));
    }

    /** JavaScript's encodeURIComponent. */
    static String encode(String s) {
        StringBuilder b = new StringBuilder();
        for (byte x : s.getBytes(StandardCharsets.UTF_8)) {
            int c = x & 0xff;
            if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || "-_.!~*'()".indexOf(c) >= 0) {
                b.append((char) c);
            } else {
                b.append('%').append(Character.toUpperCase(Character.forDigit(c >> 4, 16)))
                    .append(Character.toUpperCase(Character.forDigit(c & 15, 16)));
            }
        }
        return b.toString();
    }

    /** A page's title as the viewer shows it: its first level 1 heading, or its id. */
    public static String title(String id, String markdown) {
        Matcher m = TITLE.matcher(markdown);
        return m.find() ? DocSlugs.plainHeading(m.group(1)) : id;
    }

    /** One heading line, or null: its level and its text as the viewer's Markdown reader reads it. */
    record Heading(int depth, String text) {
    }

    static Heading heading(String line) {
        Matcher m = HEADING.matcher(line);
        if (!m.matches()) {
            return null;
        }
        String text = DocSlugs.jsTrim(m.group(2));
        if (text.endsWith("#")) {
            int end = text.length();
            while (end > 0 && text.charAt(end - 1) == '#') {
                end--;
            }
            String trimmed = text.substring(0, end);
            if (trimmed.isEmpty() || trimmed.endsWith(" ")) {
                text = DocSlugs.jsTrim(trimmed);
            }
        }
        return new Heading(m.group(1).length(), text);
    }

    /** Tracks fenced code blocks line by line. */
    private static final class Fences {
        private char ch;
        private int length;

        boolean inside() {
            return length > 0;
        }

        /** Whether this line opens or closes a fence (and so is not content). */
        boolean step(String line) {
            Matcher m = FENCE_OPEN.matcher(line);
            if (!inside()) {
                if (m.matches() && !(m.group(1).charAt(0) == '`' && m.group(2).contains("`"))) {
                    ch = m.group(1).charAt(0);
                    length = m.group(1).length();
                    return true;
                }
                return false;
            }
            if (m.matches() && m.group(1).charAt(0) == ch && m.group(1).length() >= length && m.group(2).isBlank()) {
                length = 0;
                return true;
            }
            return false;
        }
    }

    /** The anchors of every section heading on a page (level 2 and below), in order: what the viewer makes. */
    public static List<String> headingSlugs(String markdown) {
        List<String> out = new ArrayList<>();
        Map<String, Integer> seen = new HashMap<>();
        Fences fences = new Fences();
        for (String line : lines(markdown)) {
            if (fences.step(line) || fences.inside()) {
                continue;
            }
            Heading h = heading(line);
            if (h != null && h.depth() > 1) {
                out.add(DocSlugs.uniqueSlug(DocSlugs.slug(DocSlugs.plainHeading(h.text())), seen));
            }
        }
        return out;
    }

    /** The sections of a page, in order. Sections with no text are kept: their heading alone may answer. */
    public static List<Section> split(Page page) {
        List<Section> out = new ArrayList<>();
        Map<String, Integer> seen = new HashMap<>();
        Fences fences = new Fences();
        String h2 = null;
        String slug = null;
        int depth = 1;
        String path = page.title();
        StringBuilder raw = new StringBuilder();
        boolean titleSkipped = false;
        for (String line : lines(page.markdown())) {
            boolean fence = fences.step(line);
            if (!fence && !fences.inside()) {
                Heading h = heading(line);
                if (h != null && h.depth() == 1 && !titleSkipped && slug == null) {
                    titleSkipped = true; // the page title, shown above every section
                    continue;
                }
                if (h != null && h.depth() > 1) {
                    String text = DocSlugs.plainHeading(h.text());
                    String s = DocSlugs.uniqueSlug(DocSlugs.slug(text), seen);
                    if (h.depth() <= 3) {
                        add(out, page, slug, depth, path, raw);
                        raw.setLength(0);
                        slug = s;
                        depth = h.depth();
                        if (h.depth() == 2) {
                            h2 = text;
                            path = page.title() + PATH_SEPARATOR + text;
                        } else {
                            path = page.title() + (h2 == null ? "" : PATH_SEPARATOR + h2) + PATH_SEPARATOR + text;
                        }
                        continue;
                    }
                    raw.append(text).append('\n');
                    continue;
                }
            }
            raw.append(line).append('\n');
        }
        add(out, page, slug, depth, path, raw);
        return out;
    }

    private static void add(List<Section> out, Page page, String slug, int depth, String path, StringBuilder raw) {
        String body = plain(raw.toString());
        if (slug == null && body.isBlank()) {
            return; // no introduction before the first section
        }
        String id = slug == null ? page.id() : page.id() + "/" + slug;
        out.add(new Section(page.id(), slug, id, depth, path, body, out.size()));
    }

    private static List<String> lines(String markdown) {
        return List.of(markdown.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1));
    }

    /**
     * Markdown as plain text for search and for the model: link and emphasis marks go, code is
     * kept as written, tables keep their cells (without the rule under the header row).
     */
    static String plain(String markdown) {
        StringBuilder b = new StringBuilder();
        Fences fences = new Fences();
        for (String line : lines(markdown)) {
            if (fences.step(line)) {
                continue;
            }
            if (fences.inside()) {
                b.append(line).append('\n');
                continue;
            }
            if (TABLE_RULE.matcher(line).matches() && line.contains("-") && line.contains("|")) {
                continue;
            }
            String s = QUOTE.matcher(line).replaceFirst("");
            Heading h = heading(s);
            if (h != null) {
                s = h.text();
            }
            s = IMAGE_OR_LINK.matcher(s).replaceAll(r -> Matcher.quoteReplacement(r.group(1)));
            s = STRONG.matcher(s).replaceAll("");
            s = EMPHASIS.matcher(s).replaceAll(r -> Matcher.quoteReplacement(r.group(1)));
            s = s.replace("`", "");
            String t = s.strip();
            if (t.startsWith("|") && t.endsWith("|") && t.length() > 1) {
                s = String.join(" | ", t.substring(1, t.length() - 1).split("(?<!\\\\)\\|", -1)).replaceAll(" {2,}", " ")
                    .strip();
            }
            b.append(s.stripTrailing()).append('\n');
        }
        return b.toString().replaceAll("\n{3,}", "\n\n").strip();
    }
}

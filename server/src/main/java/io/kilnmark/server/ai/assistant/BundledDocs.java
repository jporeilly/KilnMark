package io.kilnmark.server.ai.assistant;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.text.Collator;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * The documentation that ships in this build: {@code docs/guide/*.md}, the install guides in
 * {@code docs/guide/install/} (copied beside the others, as their ids are their file names) and
 * {@code CHANGELOG.md} with the fragments in {@code changelog.d} merged into its Unreleased part,
 * copied into the server's resources at build time (server/build.gradle.kts, the same text the UI
 * bundles), so the assistant never cites a page that is not in this build. Pages are in the
 * viewer's order and carry its ids: the guides by title, then {@code whats-new}.
 */
public final class BundledDocs {

    /** The changelog's page id and title, as in ui/src/docs/links.ts and index.ts. */
    public static final String WHATS_NEW = "whats-new";
    public static final String WHATS_NEW_TITLE = "What's new";
    static final String ROOT = "kilnmark/docs/";

    private BundledDocs() {
    }

    /** Every bundled page. Missing resources (a build without docs) give an empty list. */
    public static List<DocSections.Page> pages() {
        String list = read(ROOT + "guide.list");
        List<DocSections.Page> guides = new ArrayList<>();
        if (list != null) {
            for (String name : list.split("\n")) {
                String file = name.strip();
                if (!file.endsWith(".md") || file.contains("/") || file.contains("\\")) {
                    continue;
                }
                String markdown = read(ROOT + "guide/" + file);
                if (markdown != null) {
                    String id = file.substring(0, file.length() - 3);
                    guides.add(new DocSections.Page(id, DocSections.title(id, markdown), markdown));
                }
            }
        }
        // The viewer sorts with localeCompare(..., "en-GB"); a collator at primary strength comes closest.
        Collator collator = Collator.getInstance(Locale.UK);
        guides.sort(Comparator.comparing(DocSections.Page::title, collator));
        String changelog = read(ROOT + "CHANGELOG.md");
        if (changelog != null) {
            guides.add(new DocSections.Page(WHATS_NEW, WHATS_NEW_TITLE, changelog));
        }
        return guides;
    }

    /**
     * The sections the assistant answers from: every section of every bundled page, except the
     * changelog's Unreleased part, which describes work not yet in a release (the viewer still
     * shows it). A release renames it to its version, and it is answered from then on.
     */
    public static List<DocSections.Section> sections() {
        String unreleased = WHATS_NEW_TITLE + DocSections.PATH_SEPARATOR + "Unreleased";
        List<DocSections.Section> out = new ArrayList<>();
        for (DocSections.Page p : pages()) {
            for (DocSections.Section s : DocSections.split(p)) {
                boolean draft = p.id().equals(WHATS_NEW) && (s.titlePath().equals(unreleased)
                    || s.titlePath().startsWith(unreleased + DocSections.PATH_SEPARATOR));
                if (!draft) {
                    out.add(s);
                }
            }
        }
        return out;
    }

    private static String read(String resource) {
        ClassLoader cl = Thread.currentThread().getContextClassLoader();
        if (cl == null) {
            cl = BundledDocs.class.getClassLoader();
        }
        try (InputStream in = cl.getResourceAsStream(resource)) {
            return in == null ? null : new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read the bundled documentation " + resource, e);
        }
    }
}

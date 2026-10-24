package io.kilnmark.kiln.plugins.documents;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import org.xml.sax.Attributes;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.DefaultHandler;

/**
 * Collects a document's text from the XHTML events Tika's parsers send: paragraphs end in a blank
 * line, list items and table rows in a line break, and the start of each page (PDF) or slide
 * (presentations) is remembered so chunks can say where they came from. The document's head
 * (title, metadata), scripts and styles are left out.
 *
 * <p>It is also where a read is stopped: once its {@link Budget} of characters is spent it throws
 * {@link LimitReached}, once the read is cancelled (its time is up) it throws {@link Cancelled},
 * and an element nesting deeper than {@value #MAX_NESTING} throws, so a hostile file cannot keep a
 * parser busy producing text nobody keeps.
 */
final class TextCollector extends DefaultHandler {

    /** Elements nested deeper than this end the read. */
    static final int MAX_NESTING = 1000;

    /** Characters that mark search highlights; never kept in text. */
    static final char MARK_START = '';
    static final char MARK_END = '';

    private static final Set<String> BLOCKS = Set.of("p", "div", "h1", "h2", "h3", "h4", "h5", "h6", "blockquote",
        "pre", "table", "ul", "ol", "dl", "section", "article", "header", "footer", "aside", "main", "nav", "hr",
        "figure", "address");
    private static final Set<String> LINES = Set.of("br", "li", "tr", "dt", "dd", "caption");
    private static final Set<String> CELLS = Set.of("td", "th");
    private static final Set<String> HIDDEN = Set.of("script", "style", "head", "noscript", "template");

    /** The character limit was reached: the text so far is kept. */
    static final class LimitReached extends SAXException {
        @java.io.Serial
        private static final long serialVersionUID = 1L;

        LimitReached() {
            super("The character limit was reached");
        }
    }

    /** The read was cancelled because its time ran out. */
    static final class Cancelled extends SAXException {
        @java.io.Serial
        private static final long serialVersionUID = 1L;

        Cancelled() {
            super("The read was stopped");
        }
    }

    /** Characters one read may still keep, shared by a document and its attachments. */
    static final class Budget {
        private int left;

        Budget(int characters) {
            this.left = characters;
        }

        int left() {
            return left;
        }
    }

    private final StringBuilder text = new StringBuilder();
    private final List<Integer> pageStarts = new ArrayList<>();
    private final List<String> pageLabels = new ArrayList<>();
    private final Budget budget;
    private final AtomicBoolean cancelled;
    private int nesting;
    private int hidden;
    private int embedded;
    private boolean truncated;
    private boolean awaitingLabel;
    private StringBuilder label;

    TextCollector(Budget budget, AtomicBoolean cancelled) {
        this.budget = budget;
        this.cancelled = cancelled;
    }

    /** The text collected, without trailing white space. */
    String text() {
        int end = text.length();
        while (end > 0 && Character.isWhitespace(text.charAt(end - 1))) {
            end--;
        }
        return text.substring(0, end);
    }

    /** Where each page or slide starts in {@link #text()}, in order; empty when the format has none. */
    List<Integer> pageStarts() {
        int end = text().length();
        return pageStarts.stream().map(p -> Math.min(p, end)).toList();
    }

    /**
     * Each page's own heading, where the format gives one (a workbook's sheet names), else null;
     * one per {@link #pageStarts()}.
     */
    List<String> pageLabels() {
        return java.util.Collections.unmodifiableList(pageLabels);
    }

    /** Whether the character limit cut the text short. */
    boolean truncated() {
        return truncated;
    }

    /** Text from a document inside this one follows; its pages are not this document's pages. */
    void enterEmbedded() throws SAXException {
        check();
        paragraph();
        embedded++;
    }

    void leaveEmbedded() {
        embedded--;
        paragraph();
    }

    private void check() throws SAXException {
        if (cancelled.get() || Thread.currentThread().isInterrupted()) {
            throw new Cancelled();
        }
        if (truncated) {
            throw new LimitReached();
        }
    }

    private static String name(String localName, String qName) {
        String n = localName == null || localName.isEmpty() ? qName : localName;
        return n == null ? "" : n.toLowerCase(Locale.ROOT);
    }

    @Override
    public void startElement(String uri, String localName, String qName, Attributes atts) throws SAXException {
        check();
        if (++nesting > MAX_NESTING) {
            throw new SAXException("its structure is nested more than " + MAX_NESTING + " levels deep");
        }
        String n = name(localName, qName);
        if (HIDDEN.contains(n)) {
            hidden++;
            return;
        }
        if (n.equals("div") && embedded == 0 && hidden == 0) {
            String cls = atts == null ? null : atts.getValue("class");
            if ("page".equals(cls) || "slide-content".equals(cls) || "sheet".equals(cls)) {
                paragraph();
                pageStarts.add(text.length());
                pageLabels.add(null);
                awaitingLabel = true;
                return;
            }
        }
        if (n.equals("h1") && awaitingLabel && embedded == 0) {
            label = new StringBuilder();
        }
        if (BLOCKS.contains(n)) {
            paragraph();
        } else if (LINES.contains(n)) {
            line();
        }
    }

    @Override
    public void endElement(String uri, String localName, String qName) throws SAXException {
        nesting--;
        String n = name(localName, qName);
        if (HIDDEN.contains(n)) {
            hidden = Math.max(0, hidden - 1);
            return;
        }
        if (n.equals("h1") && label != null) {
            String l = label.toString().strip();
            if (!l.isEmpty()) {
                pageLabels.set(pageLabels.size() - 1, l.length() > 200 ? l.substring(0, 200) : l);
            }
            label = null;
            awaitingLabel = false;
        }
        if (BLOCKS.contains(n)) {
            paragraph();
        } else if (LINES.contains(n)) {
            line();
        } else if (CELLS.contains(n)) {
            space();
        }
    }

    @Override
    public void characters(char[] ch, int start, int length) throws SAXException {
        check();
        if (hidden > 0) {
            return;
        }
        if (label != null) {
            label.append(ch, start, Math.min(length, 400));
        } else if (awaitingLabel && embedded == 0 && !new String(ch, start, length).isBlank()) {
            awaitingLabel = false; // the page starts with text, not a heading
        }
        for (int i = start; i < start + length; i++) {
            char c = ch[i];
            if (c == '\r' || c == MARK_START || c == MARK_END || c == '﻿' || (c < 0x20 && c != '\n' && c != '\t')) {
                continue;
            }
            if (c == ' ') {
                c = ' ';
            }
            if (c == '\n' && endsWithBlankLine()) {
                continue;
            }
            if ((c == ' ' || c == '\t') && (text.isEmpty() || endsWith(' ') || endsWith('\n'))) {
                continue; // runs of spaces, and spaces at the start of a line
            }
            if (budget.left <= 0) {
                truncated = true;
                throw new LimitReached();
            }
            budget.left--;
            text.append(c);
        }
    }

    @Override
    public void ignorableWhitespace(char[] ch, int start, int length) {
        // the parsers' own layout white space; breaks come from the elements
    }

    private boolean endsWith(char c) {
        return !text.isEmpty() && text.charAt(text.length() - 1) == c;
    }

    private boolean endsWithBlankLine() {
        int n = text.length();
        return n == 0 || (n >= 2 && text.charAt(n - 1) == '\n' && text.charAt(n - 2) == '\n');
    }

    private void trimSpaces() {
        while (endsWith(' ') || endsWith('\t')) {
            text.setLength(text.length() - 1);
        }
    }

    /** Ends the current paragraph with a blank line. */
    private void paragraph() {
        trimSpaces();
        if (text.isEmpty()) {
            return;
        }
        if (!endsWith('\n')) {
            append('\n');
        }
        if (!endsWithBlankLine()) {
            append('\n');
        }
    }

    /** Layout characters count against the budget too, but never end the read. */
    private void append(char c) {
        if (budget.left > 0) {
            budget.left--;
            text.append(c);
        }
    }

    private void line() {
        trimSpaces();
        if (!text.isEmpty() && !endsWith('\n')) {
            append('\n');
        }
    }

    private void space() {
        if (!text.isEmpty() && !endsWith(' ') && !endsWith('\n')) {
            append(' ');
        }
    }
}

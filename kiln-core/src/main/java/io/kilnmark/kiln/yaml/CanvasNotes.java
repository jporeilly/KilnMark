package io.kilnmark.kiln.yaml;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Notes on the canvas of a pipeline or a flow (docs/design/pipelines.md section 2): the top-level
 * {@code notes:} list of sticky notes, and the short {@code note:} any node may have. Reading
 * checks only their shape and limits, and says what is wrong; nothing a note holds is kept in the
 * definition a run, its check, its prediction or its lineage is made from, so a note never changes
 * what runs. A note is free text a designer writes: it is never masked, so it must never hold data
 * values.
 */
public final class CanvasNotes {

    /** The top-level key of the notes on the canvas. */
    public static final String KEY = "notes";
    /** The key of a node's own note. */
    public static final String NODE_KEY = "note";
    /** Most notes on one canvas. */
    public static final int MAX_NOTES = 50;
    /** Longest note on the canvas, in characters. */
    public static final int MAX_TEXT = 2_000;
    /** Longest note of a node, in characters. */
    public static final int MAX_NODE_NOTE = 280;
    /** The colours a note may have, the first when it says none; the canvas draws each with theme colours. */
    public static final List<String> COLOURS = List.of("yellow", "blue", "green", "pink", "grey");
    /** How far from the canvas's origin, or from its node, a note may be placed, either way. */
    static final int MAX_PLACE = 100_000;
    /** The least and most a note may measure across and down, in canvas pixels. */
    static final int MIN_WIDTH = 120;
    static final int MAX_WIDTH = 1_200;
    static final int MIN_HEIGHT = 60;
    static final int MAX_HEIGHT = 1_200;

    static final Set<String> NOTE_KEYS = Set.of("text", "node", "x", "y", "width", "height", "colour");

    private CanvasNotes() {
    }

    /**
     * Checks a top-level {@code notes:} list: at most {@value #MAX_NOTES} notes, each a mapping with
     * {@code text} (at most {@value #MAX_TEXT} characters) and optionally {@code node} (the node it is
     * attached to), {@code x} and {@code y}, {@code width} and {@code height}, and {@code colour}.
     * A note attached to a node the canvas does not have is drawn from the canvas's origin instead: it
     * is never a problem, since the node may be renamed in the YAML first.
     */
    static void read(YamlReader.Entry e, List<Problem> problems) {
        if (e == null || (e.value() instanceof YamlReader.Scalar sc && sc.value() == null)) {
            return;
        }
        if (!(e.value() instanceof YamlReader.Sequence list)) {
            problems.add(Problem.at(e.value().position(), null,
                "notes must be a list, each note starting with - text: …"));
            return;
        }
        if (list.items().size() > MAX_NOTES) {
            problems.add(Problem.at(list.position(), null, "A canvas has at most " + MAX_NOTES + " notes; this one has "
                + list.items().size() + ". Remove some, or put what they say together."));
        }
        int n = 0;
        for (YamlReader.Node item : list.items()) {
            n++;
            if (n > MAX_NOTES) {
                break; // said once, above
            }
            note(item, n, problems);
        }
    }

    private static void note(YamlReader.Node item, int n, List<Problem> problems) {
        String which = "Note " + n;
        if (!(item instanceof YamlReader.Mapping m)) {
            problems.add(Problem.at(item.position(), null, which + " must be a mapping starting with - text: …"));
            return;
        }
        for (YamlReader.Entry k : m.entries().values()) {
            if (!NOTE_KEYS.contains(k.key())) {
                problems.add(Problem.at(k.keyPosition(), null, which + " has no key '" + Suggest.clip(k.key()) + "'"
                    + Suggest.hint(k.key(), NOTE_KEYS) + ": a note has text, node, x, y, width, height and colour"));
            }
        }
        YamlReader.Entry text = m.entries().get("text");
        if (text == null) {
            problems.add(Problem.at(m.position(), null, which + " needs text: what it says"));
        } else {
            text(text, which + "'s text", MAX_TEXT, null, problems);
        }
        YamlReader.Entry node = m.entries().get("node");
        if (node != null && !(node.value() instanceof YamlReader.Scalar s && s.value() instanceof String id
            && PipelineCompiler.NODE_ID.matcher(id).matches())) {
            problems.add(Problem.at(node.value().position(), null, which + "'s node must be the id of the node it is "
                + "attached to, such as tidy"));
        }
        number(m.entries().get("x"), which + "'s x", -MAX_PLACE, MAX_PLACE, problems);
        number(m.entries().get("y"), which + "'s y", -MAX_PLACE, MAX_PLACE, problems);
        number(m.entries().get("width"), which + "'s width", MIN_WIDTH, MAX_WIDTH, problems);
        number(m.entries().get("height"), which + "'s height", MIN_HEIGHT, MAX_HEIGHT, problems);
        YamlReader.Entry colour = m.entries().get("colour");
        if (colour != null && !(colour.value().value() instanceof String c && COLOURS.contains(c))) {
            problems.add(Problem.at(colour.value().position(), null, which + "'s colour is one of "
                + String.join(", ", COLOURS)));
        }
    }

    /**
     * Checks a node's {@code note:}: text of at most {@value #MAX_NODE_NOTE} characters, shown on its
     * card on the canvas. Nothing else reads it.
     */
    static void readNodeNote(YamlReader.Entry e, String nodeId, List<Problem> problems) {
        if (e != null) {
            text(e, "Node " + nodeId + "'s note", MAX_NODE_NOTE, nodeId, problems);
        }
    }

    /** The comment the canvas writes above its own top-level keys. */
    private static final Pattern CANVAS_COMMENT = Pattern.compile("^#.*Kilnmark ignores this.*");

    /**
     * The YAML of a pipeline or flow without what only the canvas reads: the {@code layout:} and
     * {@code notes:} blocks, with the comment the canvas writes above each and the blank lines before
     * it, and each node's {@code note:} (written on lines of its own). Two versions with the same text
     * here run the same: moving nodes and changing notes is no change to what runs. Line breaks are
     * made {@code \n}, and the text ends in exactly one. Only a mapping written as blocks, one
     * top-level key a line, is taken apart, by where the parser found each key; any other YAML (a root
     * in braces, YAML that cannot be read) is compared as written, so a real change is never hidden.
     * The canvas compares versions the same way (ui/src/pipelines/canvas/notes.ts).
     */
    public static String withoutCanvas(String yaml) {
        // As the parser counts lines: a lone CR ends a line too.
        String text = yaml == null ? "" : yaml.replace("\r\n", "\n").replace('\r', '\n');
        List<String> lines = new ArrayList<>(List.of(text.split("\n", -1)));
        YamlReader.Mapping top = blockRoot(text, lines);
        Set<Integer> drop = new HashSet<>();
        if (top != null) {
            drop.addAll(nodeNoteLines(top, lines));
            List<Integer> keyLines = top.entries().values().stream().map(e -> e.keyPosition().line() - 1).sorted().toList();
            for (YamlReader.Entry e : top.entries().values()) {
                if (!e.key().equals(PipelineDefinition.LAYOUT) && !e.key().equals(KEY)) {
                    continue;
                }
                int at = e.keyPosition().line() - 1;
                int end = keyLines.stream().filter(l -> l > at).findFirst().orElse(lines.size());
                int start = at;
                while (start > 0 && CANVAS_COMMENT.matcher(lines.get(start - 1)).matches()) {
                    start--;
                }
                while (start > 0 && lines.get(start - 1).isBlank()) {
                    start--;
                }
                for (int i = start; i < end; i++) {
                    drop.add(i);
                }
            }
        }
        List<String> kept = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            if (!drop.contains(i)) {
                kept.add(lines.get(i));
            }
        }
        String out = String.join("\n", kept);
        int end = out.length();
        while (end > 0 && out.charAt(end - 1) == '\n') {
            end--;
        }
        return out.substring(0, end) + "\n";
    }

    /**
     * The root mapping when it is written as blocks: every top-level key at the start of a line of its
     * own. Null for YAML that cannot be read, a root in braces, or anything else.
     */
    private static YamlReader.Mapping blockRoot(String text, List<String> lines) {
        YamlReader.Node root;
        try {
            root = YamlReader.read(text);
        } catch (YamlReader.Failure f) {
            return null;
        }
        if (!(root instanceof YamlReader.Mapping top)) {
            return null;
        }
        // A mapping in braces starts at its brace; one written as blocks at its first key.
        int rootLine = top.position().line() - 1;
        int rootColumn = top.position().column() - 1;
        if (rootLine < 0 || rootLine >= lines.size() || rootColumn >= lines.get(rootLine).length()
            || lines.get(rootLine).charAt(rootColumn) == '{') {
            return null;
        }
        Set<Integer> seen = new HashSet<>();
        for (YamlReader.Entry e : top.entries().values()) {
            int line = e.keyPosition().line() - 1;
            if (e.keyPosition().column() != 1 || line >= lines.size() || !seen.add(line)
                || !lines.get(line).startsWith(e.key())) {
                return null;
            }
        }
        return top;
    }

    /**
     * The lines (from 0) of each node's {@code note:} written on lines of its own: its key's line and
     * the lines under it indented further. A note sharing its line with something else (the node's
     * {@code - }, or a mapping in braces) is left in: then a change to it counts as a change.
     */
    private static Set<Integer> nodeNoteLines(YamlReader.Mapping top, List<String> lines) {
        Set<Integer> out = new HashSet<>();
        YamlReader.Entry nodes = top.entries().containsKey("nodes") ? top.entries().get("nodes") : top.entries().get("steps");
        if (nodes == null || !(nodes.value() instanceof YamlReader.Sequence list)) {
            return out;
        }
        for (YamlReader.Node item : list.items()) {
            if (!(item instanceof YamlReader.Mapping m) || !(m.entries().get(NODE_KEY) instanceof YamlReader.Entry note)) {
                continue;
            }
            // A node in braces may go on over several lines: its note is left in, so nothing else goes with it.
            int nodeLine = m.position().line() - 1;
            int nodeColumn = m.position().column() - 1;
            if (nodeLine < 0 || nodeLine >= lines.size() || nodeColumn >= lines.get(nodeLine).length()
                || lines.get(nodeLine).charAt(nodeColumn) == '{') {
                continue;
            }
            int line = note.keyPosition().line() - 1;
            int column = note.keyPosition().column() - 1;
            if (line < 0 || line >= lines.size() || column > lines.get(line).length()
                || !lines.get(line).substring(0, column).isBlank()) {
                continue;
            }
            out.add(line);
            List<Integer> blanks = new ArrayList<>();
            for (int i = line + 1; i < lines.size(); i++) {
                String l = lines.get(i);
                if (l.isBlank()) {
                    blanks.add(i);
                } else if (l.length() - l.stripLeading().length() > column) {
                    out.addAll(blanks);
                    blanks.clear();
                    out.add(i);
                } else {
                    break;
                }
            }
        }
        return out;
    }

    private static void text(YamlReader.Entry e, String what, int most, String nodeId, List<Problem> problems) {
        // Empty text ("") is allowed: a note just added on the canvas has none yet.
        if (!(e.value() instanceof YamlReader.Scalar sc) || sc.value() == null) {
            problems.add(Problem.at(e.value().position(), nodeId, what + " must be text"));
            return;
        }
        int length = sc.written().length();
        if (length > most) {
            problems.add(Problem.at(e.value().position(), nodeId, what + " is " + length + " characters long: at most "
                + most + " fit. Shorten it, or " + (nodeId == null ? "split it into two notes."
                : "say the rest in a note on the canvas.")));
        }
    }

    private static void number(YamlReader.Entry e, String what, int least, int most, List<Problem> problems) {
        if (e == null) {
            return;
        }
        Object v = e.value().value();
        boolean ok = v instanceof Integer || v instanceof Long || v instanceof BigDecimal;
        if (ok) {
            double d = ((Number) v).doubleValue();
            ok = d >= least && d <= most;
        }
        if (!ok || v instanceof BigInteger) {
            problems.add(Problem.at(e.value().position(), null, what + " is a number from " + least + " to " + most));
        }
    }
}

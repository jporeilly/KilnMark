package io.kilnmark.kiln.yaml;

import java.io.Serial;
import java.math.BigInteger;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import org.snakeyaml.engine.v2.api.LoadSettings;
import org.snakeyaml.engine.v2.api.lowlevel.Parse;
import org.snakeyaml.engine.v2.events.CollectionStartEvent;
import org.snakeyaml.engine.v2.events.Event;
import org.snakeyaml.engine.v2.events.NodeEvent;
import org.snakeyaml.engine.v2.events.ScalarEvent;
import org.snakeyaml.engine.v2.exceptions.Mark;
import org.snakeyaml.engine.v2.exceptions.MarkedYamlEngineException;
import org.snakeyaml.engine.v2.exceptions.YamlEngineException;
import org.snakeyaml.engine.v2.nodes.Tag;
import org.snakeyaml.engine.v2.resolver.ScalarResolver;
import org.snakeyaml.engine.v2.schema.CoreSchema;

/**
 * Reads one YAML 1.2 document (core schema) into a tree that remembers where each value was
 * written. Built on SnakeYAML Engine's event parser, which never constructs objects from tags.
 * Refused, with the place: more than {@link #MAX_CHARS} characters, more than one document,
 * anchors and aliases, tags, duplicate keys, keys that are not plain values, nesting deeper than
 * {@link #MAX_DEPTH} and more than {@link #MAX_VALUES} values. Environment variables are never
 * substituted.
 */
final class YamlReader {

    /** Largest pipeline file read, in characters. */
    static final int MAX_CHARS = 1_000_000;
    /** Deepest nesting of lists and mappings. */
    static final int MAX_DEPTH = 64;
    /** Most values (scalars, lists and mappings) in one file. */
    static final int MAX_VALUES = 100_000;

    private static final ScalarResolver CORE = new CoreSchema().getScalarResolver();

    private YamlReader() {
    }

    /** A value and where it starts. */
    sealed interface Node permits Scalar, Sequence, Mapping {
        Position position();

        /** The plain Java value: String, Integer, Long, BigInteger, Double, Boolean, null, List or Map. */
        Object value();
    }

    record Scalar(Object value, Position position) implements Node {
    }

    record Sequence(List<Node> items, Position position) implements Node {
        @Override
        public Object value() {
            List<Object> out = new ArrayList<>(items.size());
            items.forEach(i -> out.add(i.value()));
            return Collections.unmodifiableList(out);
        }
    }

    /** One key of a mapping, where the key is written, and its value. */
    record Entry(String key, Position keyPosition, Node value) {
    }

    record Mapping(Map<String, Entry> entries, Position position) implements Node {
        @Override
        public Object value() {
            Map<String, Object> out = new LinkedHashMap<>();
            entries.forEach((k, e) -> out.put(k, e.value().value()));
            return Collections.unmodifiableMap(out);
        }
    }

    /** The YAML could not be read; {@code position} says where. */
    static final class Failure extends Exception {
        @Serial
        private static final long serialVersionUID = 1L;
        private final transient Position position;

        Failure(Position position, String message) {
            super(message);
            this.position = position == null ? Position.START : position;
        }

        Position position() {
            return position;
        }
    }

    /** The document's root value; null for an empty document. */
    static Node read(String text) throws Failure {
        if (text.length() > MAX_CHARS) {
            throw new Failure(Position.START, "The pipeline is too large to read: " + text.length()
                + " characters, and at most " + MAX_CHARS + " are allowed");
        }
        LoadSettings settings = LoadSettings.builder()
            .setCodePointLimit(MAX_CHARS)
            .setMaxAliasesForCollections(0)
            .setAllowRecursiveKeys(false)
            .setUseMarks(true)
            .build();
        Builder b = new Builder();
        try {
            for (Event e : new Parse(settings).parseString(text)) {
                b.accept(e);
            }
        } catch (MarkedYamlEngineException e) {
            Position at = position(e.getProblemMark().or(e::getContextMark));
            String problem = e.getProblem() == null ? e.getMessage() : e.getProblem();
            throw new Failure(at, "This is not valid YAML: " + problem.strip());
        } catch (YamlEngineException e) {
            throw new Failure(Position.START, "This is not valid YAML: " + e.getMessage());
        }
        return b.root;
    }

    private static Position position(Optional<Mark> mark) {
        return mark.map(m -> new Position(m.getLine() + 1, m.getColumn() + 1)).orElse(Position.START);
    }

    /** Builds the tree from parser events, without recursion. */
    private static final class Builder {
        private final Deque<Open> open = new ArrayDeque<>();
        private Node root;
        private int documents;
        private int values;

        /** A list or mapping being read, and for a mapping the key waiting for its value. */
        private static final class Open {
            final Position position;
            final List<Node> items; // for a list
            final Map<String, Entry> entries; // for a mapping
            String key;
            Position keyPosition;

            Open(Position position, boolean mapping) {
                this.position = position;
                this.items = mapping ? null : new ArrayList<>();
                this.entries = mapping ? new LinkedHashMap<>() : null;
            }
        }

        void accept(Event e) throws Failure {
            Position at = position(e.getStartMark());
            switch (e.getEventId()) {
                case DocumentStart -> {
                    if (++documents > 1) {
                        throw new Failure(at, "A pipeline file holds one YAML document, but this one has more"
                            + " (a line with --- starts another)");
                    }
                }
                case Alias -> throw new Failure(at, "Aliases (*name) are not allowed in a pipeline: write the value out"
                    + " in full");
                case Scalar -> {
                    ScalarEvent s = (ScalarEvent) e;
                    check(s, at, s.getTag());
                    add(new Scalar(scalar(s), at), at);
                }
                case MappingStart, SequenceStart -> {
                    CollectionStartEvent c = (CollectionStartEvent) e;
                    check(c, at, c.getTag());
                    if (open.size() >= MAX_DEPTH) {
                        throw new Failure(at, "The pipeline nests lists and mappings more than " + MAX_DEPTH
                            + " deep");
                    }
                    open.push(new Open(at, e.getEventId() == Event.ID.MappingStart));
                }
                case MappingEnd, SequenceEnd -> {
                    Open o = open.pop();
                    add(o.items != null ? new Sequence(Collections.unmodifiableList(o.items), o.position)
                        : new Mapping(Collections.unmodifiableMap(o.entries), o.position), o.position);
                }
                default -> {
                    // stream and document ends, comments
                }
            }
        }

        private void check(NodeEvent e, Position at, Optional<String> tag) throws Failure {
            if (e.getAnchor().isPresent()) {
                throw new Failure(at, "Anchors (&name) are not allowed in a pipeline: write the value out in full");
            }
            if (tag.isPresent()) {
                String t = tag.get().startsWith(Tag.PREFIX) ? "!!" + tag.get().substring(Tag.PREFIX.length()) : tag.get();
                throw new Failure(at, "Tags (" + t + ") are not allowed in a pipeline");
            }
            if (++values > MAX_VALUES) {
                throw new Failure(at, "The pipeline has more than " + MAX_VALUES + " values");
            }
        }

        private void add(Node node, Position at) throws Failure {
            Open parent = open.peek();
            if (parent == null) {
                root = node;
            } else if (parent.items != null) {
                parent.items.add(node);
            } else if (parent.key == null) {
                if (!(node instanceof Scalar s) || s.value() == null) {
                    throw new Failure(at, "A key must be a plain word or number, not a list, a mapping or empty");
                }
                String key = String.valueOf(s.value());
                Entry before = parent.entries.get(key);
                if (before != null) {
                    throw new Failure(at, "The key '" + key + "' appears twice (first on line "
                        + before.keyPosition().line() + ")");
                }
                parent.key = key;
                parent.keyPosition = at;
            } else {
                parent.entries.put(parent.key, new Entry(parent.key, parent.keyPosition, node));
                parent.key = null;
            }
        }
    }

    /** A scalar under the YAML 1.2 core schema: quoted values are always text. */
    private static Object scalar(ScalarEvent s) {
        String v = s.getValue();
        if (!s.isPlain()) {
            return v;
        }
        Tag tag = CORE.resolve(v, Boolean.TRUE);
        if (Tag.NULL.equals(tag)) {
            return null;
        }
        if (Tag.BOOL.equals(tag)) {
            return Boolean.valueOf(v.toLowerCase(Locale.ROOT).equals("true"));
        }
        if (Tag.INT.equals(tag)) {
            return integer(v);
        }
        if (Tag.FLOAT.equals(tag)) {
            return decimal(v);
        }
        return v; // text, and ${...}, which is never substituted
    }

    private static Object integer(String v) {
        String t = v;
        boolean negative = false;
        if (t.startsWith("-") || t.startsWith("+")) {
            negative = t.charAt(0) == '-';
            t = t.substring(1);
        }
        int radix = 10;
        if (t.startsWith("0x")) {
            radix = 16;
            t = t.substring(2);
        } else if (t.startsWith("0o")) {
            radix = 8;
            t = t.substring(2);
        }
        try {
            BigInteger n = new BigInteger(t, radix);
            if (negative) {
                n = n.negate();
            }
            if (n.bitLength() < 32) {
                return n.intValue();
            }
            return n.bitLength() < 64 ? (Object) n.longValue() : n;
        } catch (NumberFormatException e) {
            return v;
        }
    }

    private static Object decimal(String v) {
        String t = v.toLowerCase(Locale.ROOT);
        if (t.endsWith(".inf")) {
            return t.startsWith("-") ? Double.NEGATIVE_INFINITY : Double.POSITIVE_INFINITY;
        }
        if (t.equals(".nan")) {
            return Double.NaN;
        }
        try {
            return Double.valueOf(v);
        } catch (NumberFormatException e) {
            return v;
        }
    }
}

package io.kilnmark.kiln.yaml;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.arrow.vector.types.pojo.ArrowType;
import org.apache.arrow.vector.types.pojo.Field;
import org.apache.arrow.vector.types.pojo.Schema;

import io.kilnmark.kiln.SettingSpec;
import io.kilnmark.kiln.SettingSpec.Kind;
import io.kilnmark.kiln.Step;
import io.kilnmark.kiln.StepException;
import io.kilnmark.kiln.StepFactories;
import io.kilnmark.kiln.StepFactory;
import io.kilnmark.kiln.StepKind;
import io.kilnmark.kiln.graph.Toys;

/** Step types for compiler tests, making the toy steps of the graph tests. */
final class ToyFactories {

    /** How many steps have been created, to show that checking creates none. */
    static final AtomicInteger CREATED = new AtomicInteger();
    /** The input schemas each union was given, by key. */
    static final List<List<String>> UNION_KEYS = new java.util.concurrent.CopyOnWriteArrayList<>();

    private ToyFactories() {
    }

    static StepFactories all() {
        return new StepFactories().register(new Numbers()).register(new Mystery()).register(new Pass())
            .register(new Filter()).register(new Union()).register(new Lookup()).register(new Sink());
    }

    static Schema columns(String... names) {
        List<Field> fields = new ArrayList<>();
        for (String n : names) {
            fields.add(Field.nullable(n, new ArrowType.Int(32, true)));
        }
        return new Schema(fields);
    }

    private abstract static class Base implements StepFactory {
        @Override
        public String label() {
            return type();
        }

        @Override
        public String help() {
            return "A step for tests.";
        }

        @Override
        public List<SettingSpec> settings() {
            return List.of();
        }
    }

    /** A source of numbers in column n. */
    static final class Numbers extends Base {
        @Override
        public String type() {
            return "numbers";
        }

        @Override
        public StepKind kind() {
            return StepKind.SOURCE;
        }

        @Override
        public List<String> inputPorts() {
            return List.of();
        }

        @Override
        public List<String> outputPorts() {
            return List.of("out");
        }

        @Override
        public List<SettingSpec> settings() {
            return List.of(SettingSpec.optional("batches", "Batches", Kind.NUMBER, 3, "How many batches."),
                SettingSpec.optional("start", "Start", Kind.NUMBER, 0, "The first number."));
        }

        @Override
        public Step create(Map<String, Object> config) {
            CREATED.incrementAndGet();
            int batches = ((Number) config.getOrDefault("batches", 3)).intValue();
            int start = ((Number) config.getOrDefault("start", 0)).intValue();
            return new Toys.Generate(batches, 10, start, -1);
        }

        @Override
        public Schema outputSchema(String port, Map<String, Schema> inputs, Map<String, Object> config) {
            return columns("n");
        }
    }

    /** A source whose columns are only known when it runs. */
    static final class Mystery extends Base {
        @Override
        public String type() {
            return "mystery";
        }

        @Override
        public StepKind kind() {
            return StepKind.SOURCE;
        }

        @Override
        public List<String> inputPorts() {
            return List.of();
        }

        @Override
        public List<String> outputPorts() {
            return List.of("out");
        }

        @Override
        public Step create(Map<String, Object> config) {
            CREATED.incrementAndGet();
            return new Toys.Generate(1, 1);
        }
    }

    static class Pass extends Base {
        @Override
        public String type() {
            return "pass";
        }

        @Override
        public StepKind kind() {
            return StepKind.TRANSFORM;
        }

        @Override
        public List<String> inputPorts() {
            return List.of("in");
        }

        @Override
        public List<String> outputPorts() {
            return List.of("out");
        }

        @Override
        public Step create(Map<String, Object> config) throws StepException {
            CREATED.incrementAndGet();
            return new Toys.Pass();
        }

        @Override
        public Schema outputSchema(String port, Map<String, Schema> inputs, Map<String, Object> config) {
            return inputs.get("in");
        }
    }

    /** Settings of every kind, and a check of its own. */
    static final class Filter extends Base {
        @Override
        public String type() {
            return "filter";
        }

        @Override
        public StepKind kind() {
            return StepKind.TRANSFORM;
        }

        @Override
        public List<String> inputPorts() {
            return List.of("in");
        }

        @Override
        public List<String> outputPorts() {
            return List.of("out", "rejected");
        }

        @Override
        public List<SettingSpec> settings() {
            return List.of(SettingSpec.required("condition", "Condition", Kind.EXPRESSION, "Rows to keep."),
                SettingSpec.choice("mode", "Mode", false, "keep", "Keep or drop.", List.of("keep", "drop")),
                SettingSpec.optional("limit", "Limit", Kind.NUMBER, null, "At most this many."),
                SettingSpec.optional("strict", "Strict", Kind.BOOLEAN, false, "Fail on nulls."),
                SettingSpec.optional("columns", "Columns", Kind.COLUMNS, null, "Columns to keep."),
                SettingSpec.optional("rename", "Rename", Kind.COLUMN_MAP, null, "New names."),
                SettingSpec.optional("checks", "Checks", Kind.RULES, null, "Rules, for the rules kind."));
        }

        @Override
        public List<String> check(Map<String, Object> config) {
            return String.valueOf(config.get("condition")).contains("bad")
                ? List.of("condition uses a column that does not exist") : List.of();
        }

        @Override
        public Step create(Map<String, Object> config) {
            CREATED.incrementAndGet();
            return new Toys.Pass();
        }

        @Override
        public Schema outputSchema(String port, Map<String, Schema> inputs, Map<String, Object> config) {
            return inputs.get("in");
        }
    }

    static final class Union extends Base {
        @Override
        public String type() {
            return "union";
        }

        @Override
        public StepKind kind() {
            return StepKind.TRANSFORM;
        }

        @Override
        public List<String> inputPorts() {
            return List.of("in*");
        }

        @Override
        public List<String> outputPorts() {
            return List.of("out");
        }

        @Override
        public Step create(Map<String, Object> config) {
            CREATED.incrementAndGet();
            return new Toys.Merge();
        }

        @Override
        public Schema outputSchema(String port, Map<String, Schema> inputs, Map<String, Object> config) {
            UNION_KEYS.add(List.copyOf(inputs.keySet()));
            return inputs.values().iterator().next();
        }
    }

    /** Adds the lookup side's columns; {@code keep} must name lookup columns. */
    static final class Lookup extends Base {
        @Override
        public String type() {
            return "lookup";
        }

        @Override
        public StepKind kind() {
            return StepKind.TRANSFORM;
        }

        @Override
        public List<String> inputPorts() {
            return List.of("main", "lookup");
        }

        @Override
        public List<String> outputPorts() {
            return List.of("out");
        }

        @Override
        public List<SettingSpec> settings() {
            return List.of(SettingSpec.optional("keep", "Keep", Kind.COLUMNS, null, "Lookup columns to add."));
        }

        @Override
        public Step create(Map<String, Object> config) {
            CREATED.incrementAndGet();
            return new Toys.Merge();
        }

        @Override
        public Schema outputSchema(String port, Map<String, Schema> inputs, Map<String, Object> config)
            throws StepException {
            Schema lookup = inputs.get("lookup");
            List<Field> fields = new ArrayList<>(inputs.get("main").getFields());
            Object keep = config.get("keep");
            if (keep instanceof List<?> names) {
                for (Object n : names) {
                    Field f = lookup.getFields().stream().filter(x -> x.getName().equals(n)).findFirst()
                        .orElseThrow(() -> new StepException("keep names " + n + ", which the lookup side does not have"));
                    fields.add(new Field("lookup_" + f.getName(), f.getFieldType(), f.getChildren()));
                }
            }
            return new Schema(fields);
        }
    }

    static final class Sink extends Base {
        @Override
        public String type() {
            return "sink";
        }

        @Override
        public StepKind kind() {
            return StepKind.SINK;
        }

        @Override
        public List<String> inputPorts() {
            return List.of("in");
        }

        @Override
        public List<String> outputPorts() {
            return List.of();
        }

        @Override
        public List<SettingSpec> settings() {
            return List.of(SettingSpec.required("path", "Path", Kind.TEXT, "Where to write."));
        }

        @Override
        public Step create(Map<String, Object> config) {
            CREATED.incrementAndGet();
            return new Toys.Collect();
        }
    }
}

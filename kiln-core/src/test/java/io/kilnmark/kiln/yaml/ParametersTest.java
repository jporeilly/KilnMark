package io.kilnmark.kiln.yaml;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

import org.junit.jupiter.api.Test;

class ParametersTest {

    private static final PipelineCompiler COMPILER = new PipelineCompiler(ToyFactories.all());

    private static final String PIPELINE = """
        kilnmark: pipeline/v1
        name: toy
        parameters:
          region: { type: text, default: EMEA }
          limit: { type: number, default: 10 }
          strict: { type: boolean, default: false }
        nodes:
          - id: a
            type: numbers
            batches: ${limit}
          - id: keep
            type: filter
            input: a
            condition: "region = ${region} and n < ${limit}"
            strict: ${strict}
          - id: out
            type: sink
            input: keep
            path: out/${region}/${vars.area}.csv
        """;

    private static final Map<String, Object> VARS = Map.of("area", "north");

    private static Compiled compile(String yaml, Map<String, Object> given) {
        return COMPILER.compile(yaml, given, VARS);
    }

    private static String problems(Compiled c) {
        return c.problems().stream().map(Problem::message).toList().toString();
    }

    @Test
    void valuesGoInAsTheSettingsKindAllows() {
        Compiled c = compile(PIPELINE, Map.of());
        assertTrue(c.ok(), problems(c));
        assertEquals("region = 'EMEA' and n < 10.0", c.configs().get("keep").get("condition"));
        assertEquals(10L, c.configs().get("a").get("batches"));
        assertEquals(false, c.configs().get("keep").get("strict"));
        assertEquals("out/EMEA/north.csv", c.configs().get("out").get("path"));

        Compiled run = compile(PIPELINE.replace("out/${region}/", "out/"), Map.of("region", "O'Brien Ltd", "limit", 2.5,
            "strict", true));
        assertTrue(run.ok(), problems(run));
        assertEquals("region = 'O''Brien Ltd' and n < 2.5", run.configs().get("keep").get("condition"));
    }

    @Test
    void aValueNeverBecomesCodeOrLeavesItsPath() {
        // Text quotes itself: whatever it holds, it is one value.
        Compiled c = compile(PIPELINE.replace("path: out/${region}/${vars.area}.csv", "path: out.csv"),
            Map.of("region", "x' or 1=1 or 'y"));
        assertTrue(c.ok(), problems(c));
        assertEquals("region = 'x'' or 1=1 or ''y' and n < 10.0", c.configs().get("keep").get("condition"));
        // In a text setting a value names part of a path, nothing more.
        for (String bad : new String[] {"../etc", "a/b", "a\\b", ".."}) {
            assertTrue(problems(compile(PIPELINE, Map.of("region", bad))).contains("may hold only letters"), bad);
        }
        assertTrue(problems(compile(PIPELINE.replace("${region} and", "'${region}' and"), Map.of()))
            .contains("inside quotes"));
    }

    @Test
    void onlyValuesTakeParameters() {
        String mode = PIPELINE.replace("strict: ${strict}", "mode: ${region}");
        assertTrue(problems(compile(mode, Map.of())).contains("cannot take a parameter"));
        String part = PIPELINE.replace("batches: ${limit}", "batches: 1${limit}");
        assertTrue(problems(compile(part, Map.of())).contains("only as its whole value"));
        String kind = PIPELINE.replace("batches: ${limit}", "batches: ${region}");
        assertTrue(problems(compile(kind, Map.of())).contains("is a number, but region is not"));
    }

    @Test
    void unknownNamesAndWrongValuesAreSaid() {
        assertTrue(problems(compile(PIPELINE.replace("${region} and", "${regon} and"), Map.of()))
            .contains("names the parameter 'regon', which is not declared (did you mean 'region'?)"));
        assertTrue(problems(COMPILER.compile(PIPELINE, Map.of(), Map.of()))
            .contains("names the server variable 'area', which is not set on this server"));
        assertTrue(problems(compile(PIPELINE, Map.of("colour", "red"))).contains("has no parameter 'colour'"));
        assertTrue(problems(compile(PIPELINE, Map.of("limit", "ten"))).contains("limit must be a number"));
        assertTrue(problems(compile(PIPELINE.replace("default: EMEA", "description: no default"), Map.of()))
            .contains("needs a default"));
    }

    @Test
    void withoutParametersDollarBracesAreText() {
        String old = "kilnmark: pipeline/v1\nname: old\nnodes:\n  - { id: a, type: numbers }\n"
            + "  - { id: out, type: sink, input: a, path: \"${HOME}/x.csv\" }\n";
        Compiled c = compile(old, Map.of());
        assertTrue(c.ok(), problems(c));
        assertEquals("${HOME}/x.csv", c.configs().get("out").get("path"));
        assertTrue(problems(compile(old, Map.of("a", 1))).contains("takes no parameters"));
        // $${ writes a plain ${ once a pipeline has parameters.
        Compiled escaped = compile(PIPELINE.replace("path: out/${region}/${vars.area}.csv", "path: \"a$${b}\""), Map.of());
        assertEquals("a${b}", escaped.configs().get("out").get("path"));
    }

    @Test
    void aDefaultCanBeAServerVariable() {
        // Inside { } a ${...} is quoted, as YAML reads { as the start of a mapping.
        String fromVar = PIPELINE.replace("default: EMEA", "default: \"${vars.area}\"");
        Compiled c = compile(fromVar, Map.of());
        assertTrue(c.ok(), problems(c));
        assertEquals("out/north/north.csv", c.configs().get("out").get("path"));
        assertTrue(problems(COMPILER.compile(fromVar, Map.of(), Map.of())).contains("server variable"));
    }

    @Test
    void aListParameterIsAnInList() {
        String yaml = PIPELINE.replace("  strict: { type: boolean, default: false }",
                "  strict: { type: boolean, default: false }\n  statuses: { type: text list, default: [delivered, shipped] }")
            .replace("condition: \"region = ${region} and n < ${limit}\"", "condition: \"status in ${statuses}\"");
        Compiled c = compile(yaml, Map.of());
        assertTrue(c.ok(), problems(c));
        assertEquals("status in ('delivered', 'shipped')", c.configs().get("keep").get("condition"));
        Compiled given = compile(yaml, Map.of("statuses", java.util.List.of("it's done")));
        assertEquals("status in ('it''s done')", given.configs().get("keep").get("condition"));
        assertTrue(problems(compile(yaml, Map.of("statuses", java.util.List.of()))).contains("a list of 1 to 200"));
        assertTrue(problems(compile(yaml, Map.of("statuses", "delivered"))).contains("a list of 1 to 200"));
        // A list is never part of a path.
        assertTrue(problems(compile(yaml.replace("out/${region}/", "out/${statuses}/"), Map.of()))
            .contains("which is a list"));
        assertTrue(problems(compile(PIPELINE.replace("type: boolean,", "type: boolean list,"), Map.of()))
            .contains("needs a type: text, number, boolean, text list or number list"));
    }

    @Test
    void aValueNeverJoinsANameInAnExpression() {
        for (String touching : new String[] {"amount_${limit} > 0", "n${limit} > 0", "is_${strict}", "${limit}x > 0",
            "col${limit}", "t.${region} > 0", "${limit}(n)"}) {
            String yaml = PIPELINE.replace("region = ${region} and n < ${limit}", touching);
            assertTrue(problems(compile(yaml, Map.of())).contains("right next to"), touching);
        }
        assertTrue(compile(PIPELINE.replace("n < ${limit}", "n < (${limit})"), Map.of()).ok());
    }

    @Test
    void whereANodeWritesTakesAValueOnlyAsPartOfItsLastName() {
        Map<String, Object> v = Map.of("t", "orders", "dot", "billing.payroll", "up", "..", "vars.area", "north");
        assertEquals("sales.orders_north", Parameters.fill("sales.${t}_${vars.area}", v, Parameters.Use.TARGET_PART)
            .text());
        assertEquals("out/orders.csv", Parameters.fill("out/${t}.csv", v, Parameters.Use.TARGET_PART).text());
        assertTrue(Parameters.fill("${t}", v, Parameters.Use.TARGET_PART).problem().contains("is only parameters"));
        assertTrue(Parameters.fill("${t}/x.csv", v, Parameters.Use.TARGET_PART).problem().contains("in a folder"));
        assertTrue(Parameters.fill("sales.${dot}", v, Parameters.Use.TARGET_PART).problem()
            .contains("only letters, digits, _ and -"));
        assertTrue(Parameters.fill("out/${up}x.csv", v, Parameters.Use.TARGET_PART).problem()
            .contains("only letters, digits, _ and -"));
    }

    @Test
    void numbersAreDecimalsOfBoundedSize() {
        assertEquals("2.0", Parameters.literal(2.0));
        assertEquals("2.5", Parameters.literal(2.5));
        assertThrows(IllegalArgumentException.class, () -> Parameters.typed("n", FlowCondition.Type.NUMBER, 1e300));
        assertThrows(IllegalArgumentException.class, () -> Parameters.typed("n", FlowCondition.Type.NUMBER, 1e-300));
        assertEquals(12.5, Parameters.typed("n", FlowCondition.Type.NUMBER, 12.5));
    }

    @Test
    void wholeAndRefersReadDollarBraces() {
        assertEquals("region", Parameters.whole(" ${region} "));
        assertNull(Parameters.whole("a${region}"));
        assertTrue(Parameters.refers("x ${a} y"));
        assertFalse(Parameters.refers("x $${a} y"));
        assertTrue(Parameters.refersToVariable("${vars.a}"));
        assertEquals("(-2.0)", Parameters.literal(-2.0));
        assertEquals("0.02", Parameters.literal(0.02));
        assertThrows(IllegalArgumentException.class, () -> Parameters.typed("t", FlowCondition.Type.TEXT, "a\u0000b"));
    }
}

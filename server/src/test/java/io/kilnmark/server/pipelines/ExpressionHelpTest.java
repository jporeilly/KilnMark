package io.kilnmark.server.pipelines;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.kilnmark.kiln.nodes.expr.ExprException;
import io.kilnmark.kiln.nodes.expr.Expression;
import io.kilnmark.kiln.nodes.expr.Type;

/**
 * The worked examples the canvas shows beside a condition (ui/src/pipelines/canvas/expression-help.json)
 * are real conditions: each parses and checks against the file's columns as a filter's condition
 * does, and gives true or false.
 */
class ExpressionHelpTest {

    static Path file() {
        return Path.of(System.getProperty("kmtest.ui.root", "../ui"), "src", "pipelines", "canvas",
            "expression-help.json");
    }

    @Test
    void everyExampleIsAConditionThatChecks() throws IOException, ExprException {
        JsonNode help = new ObjectMapper().readTree(file().toFile());
        Map<String, Type> columns = new LinkedHashMap<>();
        help.path("columns").fieldNames().forEachRemaining(name ->
            columns.put(name, Type.named(help.path("columns").path(name).asText())));
        int n = 0;
        for (JsonNode example : help.path("examples")) {
            String text = example.path("text").asText();
            Expression.Compiled c = Expression.parse(text).compile(columns);
            assertEquals(Type.BOOLEAN, c.type(), text);
            n++;
        }
        assertTrue(n >= 3, "the file has its examples");
    }

    @Test
    void andBindsTighterThanOrAsTheHelpSays() throws ExprException {
        // a or b and c is a or (b and c): the reference beside the box says so.
        Map<String, Type> bools = Map.of("a", Type.BOOLEAN, "b", Type.BOOLEAN, "c", Type.BOOLEAN);
        assertEquals(Expression.parse("a or (b and c)").view(), Expression.parse("a or b and c").view());
        assertEquals(Type.BOOLEAN, Expression.parse("not a and (b or c)").compile(bools).type());
    }
}

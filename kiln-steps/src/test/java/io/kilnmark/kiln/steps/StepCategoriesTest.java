package io.kilnmark.kiln.steps;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.ServiceLoader;
import java.util.TreeMap;

import org.junit.jupiter.api.Test;

import io.kilnmark.kiln.StepCategories;
import io.kilnmark.kiln.StepFactory;
import io.kilnmark.kiln.StepKind;

/** Every step type names its palette folder, and a type that names none gets one from its kind. */
class StepCategoriesTest {

    @Test
    void eachTransformIsInItsFolder() {
        Map<String, String> got = new TreeMap<>();
        for (StepFactory f : ServiceLoader.load(StepFactory.class)) {
            got.put(f.type(), f.category());
            assertTrue(StepCategories.ALL.contains(f.category()), f.type() + ": " + f.category());
        }
        assertEquals(Map.of("aggregate", "Aggregate", "compute", "Transform", "dedupe", "Filter and route",
            "filter", "Filter and route", "limit", "Filter and route", "lookup", "Combine", "route",
            "Filter and route", "select", "Transform", "union", "Combine"), got);
    }

    @Test
    void aTypeThatNamesNoFolderGetsOneFromItsKind() {
        assertEquals("Read", StepCategories.defaultFor(StepKind.SOURCE));
        assertEquals("Write", StepCategories.defaultFor(StepKind.SINK));
        assertEquals("Quality", StepCategories.defaultFor(StepKind.GATE));
        assertEquals("Other", StepCategories.defaultFor(StepKind.TRANSFORM));
        StepFactory plain = new StepFactory() {
            public String type() {
                return "mystery";
            }

            public StepKind kind() {
                return StepKind.SINK;
            }

            public String label() {
                return "Mystery";
            }

            public String help() {
                return "";
            }

            public java.util.List<String> inputPorts() {
                return java.util.List.of("in");
            }

            public java.util.List<String> outputPorts() {
                return java.util.List.of();
            }

            public java.util.List<io.kilnmark.kiln.SettingSpec> settings() {
                return java.util.List.of();
            }

            public io.kilnmark.kiln.Step create(Map<String, Object> config) {
                throw new UnsupportedOperationException();
            }
        };
        assertEquals("Write", plain.category());
    }
}

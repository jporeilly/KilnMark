package io.kilnmark.kiln.nodes;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.ServiceLoader;
import java.util.TreeMap;

import org.junit.jupiter.api.Test;

import io.kilnmark.kiln.NodeCategories;
import io.kilnmark.kiln.NodeFactory;
import io.kilnmark.kiln.NodeKind;

/** Every node type names its palette folder, and a type that names none gets one from its kind. */
class NodeCategoriesTest {

    @Test
    void eachTransformIsInItsFolder() {
        Map<String, String> got = new TreeMap<>();
        for (NodeFactory f : ServiceLoader.load(NodeFactory.class)) {
            got.put(f.type(), f.category());
            assertTrue(NodeCategories.ALL.contains(f.category()), f.type() + ": " + f.category());
        }
        assertEquals(Map.of("aggregate", "Aggregate", "compute", "Transform", "dedupe", "Filter and route",
            "filter", "Filter and route", "limit", "Filter and route", "lookup", "Combine", "route",
            "Filter and route", "select", "Transform", "union", "Combine"), got);
    }

    @Test
    void aTypeThatNamesNoFolderGetsOneFromItsKind() {
        assertEquals("Read", NodeCategories.defaultFor(NodeKind.SOURCE));
        assertEquals("Write", NodeCategories.defaultFor(NodeKind.SINK));
        assertEquals("Quality", NodeCategories.defaultFor(NodeKind.GATE));
        assertEquals("Other", NodeCategories.defaultFor(NodeKind.TRANSFORM));
        NodeFactory plain = new NodeFactory() {
            public String type() {
                return "mystery";
            }

            public NodeKind kind() {
                return NodeKind.SINK;
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

            public io.kilnmark.kiln.Node create(Map<String, Object> config) {
                throw new UnsupportedOperationException();
            }
        };
        assertEquals("Write", plain.category());
    }
}

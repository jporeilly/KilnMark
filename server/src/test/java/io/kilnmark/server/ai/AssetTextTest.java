package io.kilnmark.server.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * What is embedded for an asset can hold no values from the data, of sensitive columns or any
 * other: a field brings its name, type and description, and nothing else reaches the text.
 */
class AssetTextTest {

    @Test
    void embeddedTextHasNoPlaceForValues() {
        assertEquals(List.of("name", "type", "description"), Arrays.stream(AssetText.Field.class.getRecordComponents())
            .map(java.lang.reflect.RecordComponent::getName).toList());
        String text = AssetText.of("shop", null, "table", "crm", "customers", "People who buy",
            List.of(new AssetText.Field("postcode", "text", "Where they live")), AssetText.DEFAULT_MAX_CHARS);
        assertEquals("table crm.customers (customers)\nSource: shop\nDescription: People who buy\n"
            + "Fields: postcode (text): Where they live", text);
    }
}

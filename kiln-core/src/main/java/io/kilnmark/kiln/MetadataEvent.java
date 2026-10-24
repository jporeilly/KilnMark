package io.kilnmark.kiln;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;

/**
 * A fact a step observed about an asset, for example a schema, a row count or a
 * field-to-field lineage link.
 *
 * @param assetUrn   the asset the fact is about, as a Kilnmark URN
 * @param kind       what kind of fact this is
 * @param attributes the fact's details
 * @param observedAt when the step observed it
 */
public record MetadataEvent(String assetUrn, Kind kind, Map<String, String> attributes, Instant observedAt) {

    /**
     * The kinds of fact a step can report. {@code SAMPLE} carries real values (sample rows and
     * example values per value shape); whoever stores it applies the source's controls on samples.
     */
    public enum Kind { SCHEMA, ROW_COUNT, PROFILE, LINEAGE, SAMPLE }

    public MetadataEvent {
        Objects.requireNonNull(assetUrn, "assetUrn");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(observedAt, "observedAt");
        if (!assetUrn.startsWith("urn:kilnmark:")) {
            throw new IllegalArgumentException("Not a Kilnmark URN: " + assetUrn);
        }
        attributes = Map.copyOf(attributes == null ? Map.of() : attributes);
    }
}

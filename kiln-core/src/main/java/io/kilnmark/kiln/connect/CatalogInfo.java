package io.kilnmark.kiln.connect;

import java.util.List;

/**
 * What one read of a source's catalogue found: its assets, the relationships it declares between
 * them (foreign keys), and warnings for the run about what could not be read (assets are still
 * recorded). Relationships refer to assets by path.
 */
public record CatalogInfo(List<AssetInfo> assets, List<RelationshipInfo> relationships, List<String> warnings) {

    public CatalogInfo {
        assets = List.copyOf(assets == null ? List.of() : assets);
        relationships = List.copyOf(relationships == null ? List.of() : relationships);
        warnings = List.copyOf(warnings == null ? List.of() : warnings);
    }

    /** Assets and relationships, with no warnings. */
    public CatalogInfo(List<AssetInfo> assets, List<RelationshipInfo> relationships) {
        this(assets, relationships, List.of());
    }
}

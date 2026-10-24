package io.kilnmark.kiln.connect;

import java.util.List;

/**
 * What one read of a source's catalogue found: its assets, and the relationships it declares
 * between them (foreign keys). Relationships refer to assets by path.
 */
public record CatalogInfo(List<AssetInfo> assets, List<RelationshipInfo> relationships) {

    public CatalogInfo {
        assets = List.copyOf(assets == null ? List.of() : assets);
        relationships = List.copyOf(relationships == null ? List.of() : relationships);
    }
}

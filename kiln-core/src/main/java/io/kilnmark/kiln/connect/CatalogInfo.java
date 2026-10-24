package io.kilnmark.kiln.connect;

import java.util.List;

/**
 * What one read of a source's catalogue found: its assets, the relationships it declares between
 * them (foreign keys), and warnings for the run about what could not be read (assets are still
 * recorded). Relationships refer to assets by path.
 *
 * @param relationshipsRead false when the declared relationships could not be read: then
 *                          {@code relationships} is empty for that reason, not because there are
 *                          none, and the server keeps the ones it recorded before
 */
public record CatalogInfo(List<AssetInfo> assets, List<RelationshipInfo> relationships, List<String> warnings,
                          boolean relationshipsRead) {

    public CatalogInfo {
        assets = List.copyOf(assets == null ? List.of() : assets);
        relationships = List.copyOf(relationships == null ? List.of() : relationships);
        warnings = List.copyOf(warnings == null ? List.of() : warnings);
    }

    /** Assets and every relationship the source declares, with no warnings. */
    public CatalogInfo(List<AssetInfo> assets, List<RelationshipInfo> relationships) {
        this(assets, relationships, List.of(), true);
    }

    /** Assets alone, with a warning saying why the relationships could not be read. */
    public static CatalogInfo withoutRelationships(List<AssetInfo> assets, String warning) {
        return new CatalogInfo(assets, List.of(), List.of(warning), false);
    }
}

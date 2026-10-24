package io.kilnmark.graph.store;

import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * The catalogue as a tree for browsing: sources, the containers in each (schemas, folders,
 * buckets), the assets in each container; and tables with their columns for diagrams. Removed
 * assets are left out everywhere.
 */
public interface BrowseStore {

    /**
     * A source with its counts. {@code label} is its display name where the server keeps one, else
     * its name.
     */
    record SourceNode(String name, String label, String kind, int containers, int assets) {
    }

    /** A container with its asset count and the count of each kind of asset in it. */
    record ContainerNode(String name, int assets, Map<String, Integer> kinds) {
        public ContainerNode {
            kinds = Map.copyOf(kinds);
        }
    }

    record AssetNode(String urn, String name, String kind, String container, Long rowCount, int fields,
                     String description) {
    }

    /** One page of a longer list, and how many there are in all. */
    record Page<T>(List<T> items, int total, int offset, int limit) {
        public Page {
            items = List.copyOf(items);
        }
    }

    record Column(String name, int position, String sourceType, String kind, boolean nullable) {
    }

    /** A table (or view, or file with columns) as a diagram shows it. */
    record Table(String urn, String source, String container, String name, String kind, Long rowCount,
                 List<String> primaryKey, List<Column> columns) {
        public Table {
            primaryKey = List.copyOf(primaryKey);
            columns = List.copyOf(columns);
        }
    }

    List<SourceNode> sources();

    /**
     * Containers of {@code source}, by name. With {@code filter}, only containers whose name holds it
     * or that hold an asset whose name does; their counts are then of the matching assets.
     */
    Page<ContainerNode> containers(String source, String filter, int offset, int limit);

    /** Assets of {@code container} in {@code source}, by name; with {@code filter}, those whose name holds it. */
    Page<AssetNode> assets(String source, String container, String filter, int offset, int limit);

    /**
     * Assets of a container that have columns (tables, views, readable files), for a diagram: at
     * most {@code limit}, by name, with the total.
     */
    Page<Table> containerTables(String source, String container, String filter, int limit);

    /** These assets, with their columns, in the order given; unknown or removed ones left out. */
    List<Table> tables(Collection<String> urns);
}

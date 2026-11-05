package io.kilnmark.kiln.connect;

import java.util.List;

/**
 * One table or view of a database source as Explore shows it (ADR 0045): its columns, keys and
 * indexes, read live from the database's catalogue, and the database's own estimate of its rows.
 * Names and types only: no value of the table, and never the text of a default or a check.
 *
 * @param path          the table's path, as the catalogue spells it ({@code schema.table})
 * @param kind          {@code table}, {@code view} and the like, as {@link AssetInfo#kind()}
 * @param columns       in the table's order
 * @param primaryKey    the primary key's columns in key order; empty when it has none
 * @param foreignKeys   the keys of this table that point at another
 * @param referencedBy  the keys of other tables that point at this one
 * @param indexes       the table's indexes, its primary key's included
 * @param estimatedRows the database's estimate of the number of rows, or null when it has none
 */
public record TableDetail(String path, String kind, List<Column> columns, List<String> primaryKey,
                          List<ForeignKey> foreignKeys, List<ForeignKey> referencedBy, List<Index> indexes,
                          Long estimatedRows) {

    public TableDetail {
        columns = List.copyOf(columns);
        primaryKey = List.copyOf(primaryKey);
        foreignKeys = List.copyOf(foreignKeys);
        referencedBy = List.copyOf(referencedBy);
        indexes = List.copyOf(indexes);
    }

    /**
     * One column.
     *
     * @param sourceType the type as the database names it
     * @param size       its length or precision where the database gives one, or null
     * @param scale      its digits after the point where the database gives them, or null
     * @param hasDefault whether it has a default; the default's text is never read out
     * @param generated  whether the database fills it itself (an identity or a generated column)
     */
    public record Column(String name, int position, String sourceType, Integer size, Integer scale, boolean nullable,
                         boolean hasDefault, boolean generated) {
    }

    /**
     * A foreign key: {@code columns.get(i)} of {@code table} refers to {@code toColumns.get(i)} of
     * {@code toTable}, in key order. Tables are written {@code schema.table}.
     */
    public record ForeignKey(String name, String table, List<String> columns, String toTable, List<String> toColumns) {

        public ForeignKey {
            columns = List.copyOf(columns);
            toColumns = List.copyOf(toColumns);
        }
    }

    /**
     * An index, with its columns in index order. {@code primaryKey} marks the index that holds the
     * primary key; {@code method} is the database's word for its kind ({@code btree}, {@code hash},
     * {@code clustered}), or null when the driver does not say.
     */
    public record Index(String name, List<String> columns, boolean unique, boolean primaryKey, String method) {

        public Index {
            columns = List.copyOf(columns);
        }
    }
}

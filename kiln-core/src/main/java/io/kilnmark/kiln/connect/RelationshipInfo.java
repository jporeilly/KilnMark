package io.kilnmark.kiln.connect;

import java.util.List;
import java.util.Objects;

/**
 * A relationship the source itself declares between two of its assets, such as a foreign key.
 * Multi-column keys stay together: {@code fromFields.get(i)} refers to {@code toFields.get(i)},
 * in the key's own order.
 *
 * @param fromPath   the referring asset's path (as in {@link AssetInfo#path()}), for example
 *                   {@code crm.accounts}
 * @param fromFields the referring fields, in key order
 * @param toPath     the referred asset's path, which may be in another container (schema) of the
 *                   same source
 * @param toFields   the referred fields, in the same order
 * @param name       the constraint's name in the source, or null
 */
public record RelationshipInfo(String fromPath, List<String> fromFields, String toPath, List<String> toFields,
                               String name) {

    public RelationshipInfo {
        Objects.requireNonNull(fromPath, "fromPath");
        Objects.requireNonNull(toPath, "toPath");
        fromFields = List.copyOf(fromFields);
        toFields = List.copyOf(toFields);
        if (fromFields.isEmpty() || fromFields.size() != toFields.size()) {
            throw new IllegalArgumentException("A relationship needs the same number of fields at each end");
        }
    }
}

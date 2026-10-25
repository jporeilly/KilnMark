package io.kilnmark.kiln.plugins.jdbc;

import io.kilnmark.kiln.connect.ConnectorException;

/**
 * The table a pipeline writes to: {@code schema.table}, as written in the pipeline. Names are used
 * exactly as given (case included) and always quoted, so any name the database allows works; a
 * name holding a dot, a control character or leading or trailing spaces is refused.
 */
public record TableName(String schema, String table) {

    /**
     * Reads {@code schema.table}.
     *
     * @throws ConnectorException saying what to write instead
     */
    public static TableName parse(String target) throws ConnectorException {
        if (target == null || target.isBlank()) {
            throw new ConnectorException("table: give the table as schema.table, for example billing.customer_spend");
        }
        int dot = target.indexOf('.');
        if (dot < 0 || target.indexOf('.', dot + 1) >= 0) {
            throw new ConnectorException("table: '" + target + "' should be schema.table with one dot, for example "
                + "billing.customer_spend; names holding dots cannot be written to");
        }
        TableName name = new TableName(target.substring(0, dot), target.substring(dot + 1));
        check(name.schema(), "schema", target);
        check(name.table(), "table", target);
        return name;
    }

    private static void check(String part, String what, String target) throws ConnectorException {
        if (part.isEmpty() || !part.equals(part.strip())) {
            throw new ConnectorException("table: the " + what + " name in '" + target
                + "' is empty or starts or ends with a space");
        }
        for (int i = 0; i < part.length(); i++) {
            char c = part.charAt(i);
            if (c < 0x20 || c == 0x7f) {
                throw new ConnectorException("table: the " + what + " name in '" + target.replaceAll("\\p{Cntrl}", "?")
                    + "' holds a control character");
            }
        }
    }

    @Override
    public String toString() {
        return schema + "." + table;
    }
}

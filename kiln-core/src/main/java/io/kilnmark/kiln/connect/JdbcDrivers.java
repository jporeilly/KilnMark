package io.kilnmark.kiln.connect;

import java.sql.SQLException;

/**
 * The JDBC drivers the customer installed, for connectors whose driver Kilnmark cannot ship
 * ({@link Connector#driver()}). A connector asks each time it connects, so a driver installed
 * while the server runs is used at once.
 */
public interface JdbcDrivers {

    /**
     * The driver {@code spec} names, loaded from the verified jar.
     *
     * @throws SQLException with a message a person can act on (SQL state {@code 08001}) when it is not
     *                      installed, or its file is not the expected one
     */
    java.sql.Driver driver(Connector.Driver spec) throws SQLException;

    /** No drivers installed. */
    JdbcDrivers NONE = spec -> {
        throw new SQLException(missing(spec, null), "08001");
    };

    /** The message for a driver that is not installed, saying where its file goes when {@code folder} is known. */
    static String missing(Connector.Driver spec, String folder) {
        return "This kind of source needs its driver, " + spec.coordinates() + " " + spec.version() + ", which "
            + "Kilnmark does not ship because of its licence. An admin can install it under Settings, Drivers"
            + (folder == null ? "" : ", or put " + spec.fileName() + " in " + folder);
    }
}

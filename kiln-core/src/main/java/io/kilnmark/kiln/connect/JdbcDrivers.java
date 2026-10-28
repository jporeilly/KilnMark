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

    /**
     * An empty folder only this server's user can write, for drivers that would otherwise read
     * settings from a folder the environment chooses (Oracle's {@code TNS_ADMIN}); null (the
     * default) when there is none.
     */
    default java.nio.file.Path emptyFolder() {
        return null;
    }

    /**
     * The drivers an admin registered for the generic JDBC connector, by name; none by default.
     * Read each time it is asked, so a driver registered while the server runs is offered at once.
     */
    default java.util.List<RegisteredDriver> registered() {
        return java.util.List.of();
    }

    /** The registered driver called {@code name}, if there is one. */
    default java.util.Optional<RegisteredDriver> registration(String name) {
        return registered().stream().filter(r -> r.name().equals(name)).findFirst();
    }

    /**
     * The registered driver {@code r}, loaded from its jar only while the jar's SHA-256 is the one
     * recorded when it was registered, in a class loader of its own.
     *
     * @throws SQLException with a message a person can act on (SQL state {@code 08001}) when it is
     *                      gone, changed or cannot be loaded
     */
    default java.sql.Driver driver(RegisteredDriver r) throws SQLException {
        throw new SQLException(notRegistered(r.name()), "08001");
    }

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

    /** The message for a source naming a driver that is not registered (any more). */
    static String notRegistered(String name) {
        return "No JDBC driver called " + name + " is registered on this server. An admin registers drivers under "
            + "Settings, JDBC drivers";
    }
}

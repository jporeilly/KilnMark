package io.kilnmark.kiln.plugins.jdbc;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Which schemas names resolve in, on a connection to a PostgreSQL source (ADR 0028, ADR 0045).
 *
 * <p>The SQL Kilnmark writes itself (catalogue lookups, the write node's checks, reads, profiles,
 * the library's quality rules) must mean PostgreSQL's own functions, operators and types: another
 * database user may put a function or operator in a schema on the login's search path ({@code public}
 * by default on PostgreSQL 14 and older) that matches a name better than the built-in one (an
 * {@code =} for {@code oid} and {@code regclass}, an {@code rtrim(varchar)}), and it would then
 * answer for the built-in and run as the source's login. So every connection Kilnmark opens to a
 * PostgreSQL source starts with {@code search_path = pg_catalog, pg_temp}
 * ({@link #kilnmarkOnly}), and Kilnmark always names the tables it reads and writes with their
 * schema.
 *
 * <p>SQL a person wrote (a read's query, a custom rule, a wait-for query, a flow's sql node) means
 * what its author meant: it runs with the login's own search path, put back for its transaction
 * alone ({@link #asWritten}). A write puts it back for the rest of its session once its checks are
 * done ({@link #loginPath}), so the table's own triggers find what they always found.
 */
public final class PostgresSession {

    /** The search path each connection's login had, before {@link #kilnmarkOnly} changed it. */
    private static final Map<Connection, String> LOGIN_PATH = Collections.synchronizedMap(new WeakHashMap<>());

    private PostgresSession() {
    }

    /**
     * Remembers the login's search path and sets the session's to {@code pg_catalog, pg_temp}. Call
     * right after connecting, before anything else is sent. The first statement names its function
     * with its schema, so nothing planted can answer it.
     */
    public static void kilnmarkOnly(Connection c) throws SQLException {
        String path;
        try (Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT pg_catalog.current_setting('search_path')")) {
            path = rs.next() ? rs.getString(1) : null;
        }
        try (Statement st = c.createStatement()) {
            st.execute("SET search_path = pg_catalog, pg_temp");
        }
        if (path != null) {
            LOGIN_PATH.put(c, path);
        }
    }

    /**
     * The login's own search path again until the transaction in progress ends: for SQL a person
     * wrote. Nothing when {@code c} was not set up by {@link #kilnmarkOnly}.
     */
    public static void asWritten(Connection c) throws SQLException {
        set(c, true);
    }

    /** The login's own search path again for the rest of the session: a write, once its checks are done. */
    public static void loginPath(Connection c) throws SQLException {
        set(c, false);
    }

    private static void set(Connection c, boolean local) throws SQLException {
        String path = LOGIN_PATH.get(c);
        if (path == null) {
            return;
        }
        try (PreparedStatement ps = c.prepareStatement("SELECT pg_catalog.set_config('search_path', "
            + "?::pg_catalog.text, " + local + ")")) {
            ps.setString(1, path);
            ps.execute();
        }
    }

    /** Whether {@code url} is a PostgreSQL JDBC address. */
    static boolean isPostgres(String url) {
        return url != null && url.startsWith("jdbc:postgresql:");
    }
}

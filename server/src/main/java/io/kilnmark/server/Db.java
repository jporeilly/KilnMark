package io.kilnmark.server;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;

import javax.sql.DataSource;

import io.agroal.api.AgroalDataSource;
import jakarta.enterprise.context.ApplicationScoped;

/** Short transactions on the server's own database, for the server's tables (tokens, jobs). */
@ApplicationScoped
public class Db {

    /** Work done inside one transaction. */
    @FunctionalInterface
    public interface Work<T> {
        T run(Connection c) throws SQLException;
    }

    private final DataSource source;

    public Db(AgroalDataSource source) {
        this.source = source;
    }

    /** A connection outside any transaction helper; the caller closes it. */
    public Connection connection() throws SQLException {
        return source.getConnection();
    }

    /** The transaction {@link #together} keeps open on this thread, which every {@link #tx} inside it joins. */
    private static final ThreadLocal<Connection> JOINED = new ThreadLocal<>();

    /**
     * Runs {@code work} in one transaction: committed if it returns, rolled back if it throws. Inside
     * {@link #together} on the same thread, it joins that transaction instead, and commits with it.
     */
    public <T> T tx(Work<T> work) {
        Connection joined = JOINED.get();
        if (joined != null) {
            try {
                return work.run(joined);
            } catch (SQLException e) {
                throw new DbException(e.getMessage(), e);
            }
        }
        try (Connection c = source.getConnection()) {
            boolean auto = c.getAutoCommit();
            c.setAutoCommit(false);
            try {
                T result = work.run(c);
                c.commit();
                return result;
            } catch (SQLException | RuntimeException e) {
                c.rollback();
                throw e;
            } finally {
                c.setAutoCommit(auto);
            }
        } catch (SQLException e) {
            throw new DbException(e.getMessage(), e);
        }
    }

    /**
     * Runs {@code work} in one transaction that every {@link #tx} it makes on this thread joins, so
     * all of it commits together or none of it does: a run queued by code that knows nothing of the
     * caller's own rows (a request key, ADR 0047) commits with them. Work inside must not catch a
     * failed statement and go on: PostgreSQL refuses every statement after one fails.
     */
    public <T> T together(Work<T> work) {
        if (JOINED.get() != null) {
            throw new IllegalStateException("already inside together()");
        }
        return tx(c -> {
            JOINED.set(c);
            try {
                return work.run(c);
            } finally {
                JOINED.remove();
            }
        });
    }

    /** A database call failed; the message is PostgreSQL's. */
    public static final class DbException extends RuntimeException {
        @java.io.Serial
        private static final long serialVersionUID = 1L;

        public DbException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    public static Instant instant(Timestamp t) {
        return t == null ? null : t.toInstant();
    }

    public static Timestamp timestamp(Instant i) {
        return i == null ? null : Timestamp.from(i);
    }
}

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

    /** Runs {@code work} in one transaction: committed if it returns, rolled back if it throws. */
    public <T> T tx(Work<T> work) {
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

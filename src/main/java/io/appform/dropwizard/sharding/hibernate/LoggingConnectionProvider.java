package io.appform.dropwizard.sharding.hibernate;

import lombok.extern.slf4j.Slf4j;
import org.hibernate.engine.jdbc.connections.spi.ConnectionProvider;

import java.sql.Connection;
import java.sql.SQLException;

/**
 * A decorating {@link ConnectionProvider} that logs the real database connection ID
 * (e.g., MariaDB/MySQL {@code CONNECTION_ID()}) on every borrow and return.
 *
 * <p>This is useful for sanity/debugging to verify that different operations use
 * different physical connections, which is critical for validating cross-connection
 * consistency under {@code REPEATABLE_READ} isolation.
 *
 * <p>Usage: wrap any existing {@code ConnectionProvider} via the constructor.
 * All {@code ConnectionProvider} methods are delegated to the wrapped instance.
 *
 * <p>Log output example:
 * <pre>
 *   [CONN] shard=orders_shard0 BORROW connId=42 thread=main
 *   [CONN] shard=orders_shard0 RETURN connId=42 thread=main
 * </pre>
 */
@Slf4j
public class LoggingConnectionProvider implements ConnectionProvider {

    private final ConnectionProvider delegate;
    private final String shardName;

    /**
     * @param delegate  the real connection provider to wrap
     * @param shardName a human-readable label for log messages (e.g., shard URL or index)
     */
    public LoggingConnectionProvider(ConnectionProvider delegate, String shardName) {
        this.delegate = delegate;
        this.shardName = shardName;
    }

    @Override
    public Connection getConnection() throws SQLException {
        Connection conn = delegate.getConnection();
        long connId = queryConnectionId(conn);
        log.info("[CONN] shard={} BORROW connId={} thread={}",
                shardName, connId, Thread.currentThread().getName());
        return conn;
    }

    @Override
    public void closeConnection(Connection conn) throws SQLException {
        long connId = queryConnectionId(conn);
        log.info("[CONN] shard={} RETURN connId={} thread={}",
                shardName, connId, Thread.currentThread().getName());
        delegate.closeConnection(conn);
    }

    @Override
    public boolean supportsAggressiveRelease() {
        return delegate.supportsAggressiveRelease();
    }

    @Override
    @SuppressWarnings("rawtypes")
    public boolean isUnwrappableAs(Class unwrapType) {
        return delegate.isUnwrappableAs(unwrapType);
    }

    @Override
    @SuppressWarnings("rawtypes")
    public <T> T unwrap(Class<T> unwrapType) {
        return delegate.unwrap(unwrapType);
    }

    /**
     * Queries the real database connection ID. Works on MariaDB and MySQL.
     * Returns -1 if the query fails (e.g., H2 or closed connection).
     */
    private long queryConnectionId(Connection conn) {
        try (var stmt = conn.createStatement();
             var rs = stmt.executeQuery("SELECT CONNECTION_ID()")) {
            if (rs.next()) {
                return rs.getLong(1);
            }
        } catch (Exception e) {
            // Silently fall back — H2 doesn't support CONNECTION_ID()
            log.debug("[CONN] Could not query CONNECTION_ID(): {}", e.getMessage());
        }
        return -1;
    }
}

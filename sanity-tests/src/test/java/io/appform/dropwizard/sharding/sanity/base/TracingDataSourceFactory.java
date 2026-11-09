package io.appform.dropwizard.sharding.sanity.base;

import com.codahale.metrics.MetricRegistry;
import io.dropwizard.db.DataSourceFactory;
import io.dropwizard.db.ManagedDataSource;
import lombok.val;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;

/**
 * A {@link DataSourceFactory} that wraps the real {@link ManagedDataSource} and
 * {@link Connection} in dynamic proxies to record lifecycle events in
 * {@link DbEventTracker}.
 *
 * <h3>Events captured</h3>
 * <ul>
 *   <li>{@code GET_CONNECTION} — on every {@code DataSource.getConnection()} call</li>
 *   <li>{@code COMMIT} — on every {@code Connection.commit()} call</li>
 *   <li>{@code ROLLBACK} — on every {@code Connection.rollback()} call</li>
 *   <li>{@code RELEASE_CONNECTION} — on every {@code Connection.close()} call</li>
 *   <li>{@code PREPARE_STATEMENT} — on every {@code Connection.prepareStatement()} call</li>
 *   <li>{@code CREATE_STATEMENT} — on every {@code Connection.createStatement()} call</li>
 * </ul>
 *
 * <h3>autoCommit AND-latch</h3>
 * At each checkpoint (commit, rollback, prepareStatement, createStatement), the proxy
 * checks {@code connection.getAutoCommit()}. If autoCommit is ever {@code true},
 * {@link DbEventTracker#wasAutoCommitDisabledThroughout()} will return {@code false}
 * and an {@code AUTOCOMMIT_VIOLATION_AT_<stage>} event is logged for diagnosability.
 *
 * <p>{@code BEGIN_TRANSACTION} is captured separately by
 * {@link TestTransactionInterceptor} (Hibernate interceptor), not by this proxy.
 * The relative order of {@code BEGIN_TRANSACTION} and {@code GET_CONNECTION}
 * depends on Hibernate's {@code CONNECTION_PROVIDER_DISABLES_AUTOCOMMIT} setting:
 * <ul>
 *   <li>{@code true} — Hibernate defers connection acquisition until the first SQL
 *       statement. Order: {@code BEGIN_TRANSACTION} then {@code GET_CONNECTION}.</li>
 *   <li>{@code false} — Hibernate acquires the connection immediately during
 *       {@code beginTransaction()} (to call {@code setAutoCommit(false)}).
 *       Order: {@code GET_CONNECTION} then {@code BEGIN_TRANSACTION}.</li>
 * </ul>
 *
 * <h3>How it works</h3>
 * <pre>
 *   TracingDataSourceFactory.build(metrics, name)
 *     └── super.build() → real ManagedPooledDataSource (Tomcat pool)
 *     └── wraps in ManagedDataSource proxy
 *           └── getConnection() → logs GET_CONNECTION, wraps real Connection in proxy
 *                                   └── commit()   → logs COMMIT
 *                                   └── rollback() → logs ROLLBACK
 *                                   └── close()    → logs RELEASE_CONNECTION
 * </pre>
 *
 * <p>All method calls are forwarded to the real objects. No behavior is changed —
 * only observability is added.
 */
public class TracingDataSourceFactory extends DataSourceFactory {

    @Override
    public ManagedDataSource build(MetricRegistry metricRegistry, String name) {
        ManagedDataSource realDs = super.build(metricRegistry, name);
        return createDataSourceProxy(realDs);
    }

    private ManagedDataSource createDataSourceProxy(ManagedDataSource realDs) {
        return (ManagedDataSource) Proxy.newProxyInstance(
                ManagedDataSource.class.getClassLoader(),
                new Class<?>[]{ManagedDataSource.class},
                new DataSourceInvocationHandler(realDs)
        );
    }

    /**
     * Proxy handler for {@link ManagedDataSource}. Intercepts {@code getConnection()}
     * to log the event and wrap the returned connection.
     */
    private static class DataSourceInvocationHandler implements InvocationHandler {
        private final ManagedDataSource delegate;

        DataSourceInvocationHandler(ManagedDataSource delegate) {
            this.delegate = delegate;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            if ("getConnection".equals(method.getName())) {
                DbEventTracker.log(DbEventTracker.DbEvent.GET_CONNECTION);
                Connection realConn = (Connection) method.invoke(delegate, args);
                return createConnectionProxy(realConn);
            }
            try {
                return method.invoke(delegate, args);
            } catch (java.lang.reflect.InvocationTargetException e) {
                throw e.getCause();
            }
        }
    }

    /**
     * Creates a proxy around a real {@link Connection} that logs lifecycle events.
     */
    private static Connection createConnectionProxy(Connection realConn) {
        return (Connection) Proxy.newProxyInstance(
                Connection.class.getClassLoader(),
                new Class<?>[]{Connection.class},
                new ConnectionInvocationHandler(realConn)
        );
    }

    /**
     * Proxy handler for {@link Connection}. Intercepts well-known methods, logs events,
     * and checks autoCommit state at each stage via an AND-latch in {@link DbEventTracker}.
     */
    private static class ConnectionInvocationHandler implements InvocationHandler {
        private final Connection delegate;

        ConnectionInvocationHandler(Connection delegate) {
            this.delegate = delegate;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            val mname = method.getName();
            switch (method.getName()) {
                case "commit":
                    DbEventTracker.log(DbEventTracker.DbEvent.COMMIT);
                    checkAutoCommit("commit");
                    break;
                case "rollback":
                    DbEventTracker.log(DbEventTracker.DbEvent.ROLLBACK);
                    checkAutoCommit("rollback");
                    break;
                case "close":
                    DbEventTracker.log(DbEventTracker.DbEvent.RELEASE_CONNECTION);
                    break;
                case "prepareStatement":
                    DbEventTracker.log(DbEventTracker.DbEvent.PREPARE_STATEMENT);
                    checkAutoCommit("prepareStatement");
                    break;
                case "createStatement":
                    DbEventTracker.log(DbEventTracker.DbEvent.CREATE_STATEMENT);
                    checkAutoCommit("createStatement");
                    break;
                default:
                    break;
            }
            try {
                return method.invoke(delegate, args);
            } catch (java.lang.reflect.InvocationTargetException e) {
                throw e.getCause();
            }
        }

        /**
         * Checks autoCommit on the real connection and updates the AND-latch.
         * If autoCommit is true (bad), the latch flips permanently and an
         * AUTOCOMMIT_VIOLATION event is logged with the stage name.
         */
        private void checkAutoCommit(String stage) {
            try {
                DbEventTracker.checkAutoCommit(delegate.getAutoCommit(), stage);
            } catch (Exception e) {
                // Swallow — don't let observability break the actual operation
            }
        }
    }
}

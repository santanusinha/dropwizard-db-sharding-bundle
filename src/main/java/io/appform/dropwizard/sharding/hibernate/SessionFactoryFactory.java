package io.appform.dropwizard.sharding.hibernate;

import io.appform.dropwizard.sharding.healthcheck.HealthCheckManager;
import io.dropwizard.core.setup.Environment;
import io.dropwizard.db.DatabaseConfiguration;
import io.dropwizard.db.ManagedDataSource;
import io.dropwizard.db.PooledDataSourceFactory;
import io.dropwizard.util.Duration;
import lombok.extern.slf4j.Slf4j;
import org.hibernate.SessionFactory;
import org.hibernate.boot.registry.BootstrapServiceRegistry;
import org.hibernate.boot.registry.BootstrapServiceRegistryBuilder;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
import org.hibernate.cfg.AvailableSettings;
import org.hibernate.cfg.Configuration;
import org.hibernate.engine.jdbc.connections.internal.DatasourceConnectionProviderImpl;
import org.hibernate.engine.jdbc.connections.spi.ConnectionProvider;
import org.hibernate.service.ServiceRegistry;

import javax.sql.DataSource;
import java.util.List;
import java.util.Map;
import java.util.SortedSet;
import java.util.TreeSet;

import static java.util.Objects.requireNonNull;

@Slf4j
public abstract class SessionFactoryFactory<T> implements DatabaseConfiguration<T> {

    public static final String DEFAULT_NAME = "hibernate";

    private final List<Class<?>> entities;
    private final HealthCheckManager healthCheckManager;

    private SessionFactory sessionFactory;


    protected SessionFactoryFactory(final List<Class<?>> entities,
                                    final HealthCheckManager healthCheckManager) {
        this.entities = entities;
        this.healthCheckManager = healthCheckManager;
    }

    protected String name() {
        return DEFAULT_NAME;
    }

    public SessionFactorySource build(T configuration, Environment environment) throws Exception {
        final PooledDataSourceFactory dbConfig = getDataSourceFactory(configuration);
        final ManagedDataSource dataSource = dbConfig.build(environment.metrics(), name());
        final ConnectionProvider provider = buildConnectionProvider(dataSource, dbConfig.getProperties());
        this.sessionFactory = buildSessionFactory(
                dbConfig,
                provider,
                dbConfig.getProperties(),
                entities);
        healthCheckManager.register(name(), new SessionFactoryHealthCheck(
                environment.getHealthCheckExecutorService(),
                dbConfig.getValidationQueryTimeout().orElse(Duration.seconds(5)),
                sessionFactory,
                dbConfig.getValidationQuery()));
        log.info("Initialized db sharding bundle for shard {}", name());
        return SessionFactorySource.builder()
                .dataSource(dataSource)
                .factory(sessionFactory)
                .build();
    }

    private ConnectionProvider buildConnectionProvider(final DataSource dataSource,
                                                       final Map<String, String> properties) {
        final DatasourceConnectionProviderImpl connectionProvider = new DatasourceConnectionProviderImpl();
        connectionProvider.setDataSource(dataSource);
        connectionProvider.configure(properties);
        return connectionProvider;
    }

    private SessionFactory buildSessionFactory(final PooledDataSourceFactory dbConfig,
                                               final ConnectionProvider connectionProvider,
                                               final Map<String, String> properties,
                                               final List<Class<?>> entities) {

        final BootstrapServiceRegistry bootstrapServiceRegistry = new BootstrapServiceRegistryBuilder().build();
        final Configuration configuration = new Configuration(bootstrapServiceRegistry);
        configuration.setProperty(AvailableSettings.CURRENT_SESSION_CONTEXT_CLASS, "managed");
        configuration.setProperty(AvailableSettings.USE_SQL_COMMENTS, Boolean.toString(dbConfig.isAutoCommentsEnabled()));
        configuration.setProperty(AvailableSettings.USE_GET_GENERATED_KEYS, "true");
        configuration.setProperty(AvailableSettings.GENERATE_STATISTICS, "true");
        configuration.setProperty(AvailableSettings.USE_REFLECTION_OPTIMIZER, "true");
        configuration.setProperty(AvailableSettings.ORDER_UPDATES, "true");
        configuration.setProperty(AvailableSettings.ORDER_INSERTS, "true");
        configuration.setProperty(AvailableSettings.USE_NEW_ID_GENERATOR_MAPPINGS, "true");
        configuration.setProperty("jadira.usertype.autoRegisterUserTypes", "true");
        for (Map.Entry<String, String> property : properties.entrySet()) {
            configuration.setProperty(property.getKey(), property.getValue());
        }
        addAnnotatedClasses(configuration, entities);
        final ServiceRegistry registry = new StandardServiceRegistryBuilder(bootstrapServiceRegistry)
                .addService(ConnectionProvider.class, connectionProvider)
                .applySettings(configuration.getProperties())
                .build();
        return configuration.buildSessionFactory(registry);
    }

    public SessionFactory getSessionFactory() {
        return requireNonNull(sessionFactory);
    }

    private void addAnnotatedClasses(final Configuration configuration,
                                     final Iterable<Class<?>> entities) {
        final SortedSet<String> entityClasses = new TreeSet<>();
        for (Class<?> klass : entities) {
            configuration.addAnnotatedClass(klass);
            entityClasses.add(klass.getCanonicalName());
        }
        log.info("Entity classes: {}", entityClasses);
    }
}

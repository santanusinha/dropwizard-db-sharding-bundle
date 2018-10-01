/*
 * Copyright 2018 Saurabh Agrawal (Cleartax)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

package io.dropwizard.sharding.hibernate;

import io.dropwizard.setup.Environment;
import org.hibernate.SessionFactory;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
import org.hibernate.cfg.AvailableSettings;
import org.hibernate.cfg.Configuration;
import org.hibernate.engine.jdbc.connections.internal.DatasourceConnectionProviderImpl;
import org.hibernate.engine.jdbc.connections.spi.ConnectionProvider;
import org.hibernate.engine.jdbc.connections.spi.MultiTenantConnectionProvider;
import org.hibernate.service.ServiceRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.util.*;

// copy from SessionFactoryFactory
// replace DataSourceFactory with MultiTenantDataSourceFactory
// replace ConnectionProvider with MultiTenantConnectionProvider
// replace SessionFactoryManager with MultiTenantSessionFactoryManager

public class MultiTenantSessionFactoryFactory {
    private static final Logger LOGGER = LoggerFactory.getLogger(MultiTenantSessionFactoryFactory.class);
    private static final String DEFAULT_NAME = "hibernate";

    public SessionFactory build(MultiTenantHibernateBundle<?> bundle,
                                Environment environment,
                                MultiTenantDataSourceFactory dbConfig,
                                List<Class<?>> entities) {
        return build(bundle, environment, dbConfig, entities, DEFAULT_NAME);
    }

    public SessionFactory build(MultiTenantHibernateBundle<?> bundle,
                                Environment environment,
                                MultiTenantDataSourceFactory dbConfig,
                                List<Class<?>> entities,
                                String name) {
        final MultiTenantManagedDataSource dataSource = dbConfig.build(environment.metrics(), name);
        return build(bundle, environment, dbConfig, dataSource, entities);
    }

    public SessionFactory build(MultiTenantHibernateBundle<?> bundle,
                                Environment environment,
                                MultiTenantDataSourceFactory dbConfig,
                                MultiTenantManagedDataSource dataSource,
                                List<Class<?>> entities) {
        final MultiTenantConnectionProvider provider = buildMultiTenantConnectionProvider(dataSource,
                dbConfig.getProperties());
        final SessionFactory factory = buildSessionFactory(bundle,
                dbConfig,
                provider,
                dbConfig.getProperties(),
                entities);
        final MultiTenantSessionFactoryManager managedFactory = new MultiTenantSessionFactoryManager(factory, dataSource);
        environment.lifecycle().manage(managedFactory);
        return factory;
    }

    private MultiTenantConnectionProvider buildMultiTenantConnectionProvider(
            MultiTenantManagedDataSource multiTenantDataSource, Map<String, String> properties) {
        Map<String, ConnectionProvider> connectionProviderMap = new HashMap<>();
        multiTenantDataSource.getTenantDataSourceMap().forEach(
                (tenantKey,ds) -> connectionProviderMap.put(tenantKey, buildConnectionProvider(ds, properties)));
        return new ConfigurableMultiTenantConnectionProvider(connectionProviderMap);
    }

    private ConnectionProvider buildConnectionProvider(DataSource dataSource,
                                                       Map<String, String> properties) {
        final DatasourceConnectionProviderImpl connectionProvider = new DatasourceConnectionProviderImpl();
        connectionProvider.setDataSource(dataSource);
        connectionProvider.configure(properties);
        return connectionProvider;
    }

    private SessionFactory buildSessionFactory(MultiTenantHibernateBundle<?> bundle,
                                               MultiTenantDataSourceFactory dbConfig,
                                               MultiTenantConnectionProvider connectionProvider,
                                               Map<String, String> properties,
                                               List<Class<?>> entities) {
        final Configuration configuration = new Configuration();
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
        bundle.configure(configuration);

        // This is important
        configuration.setProperty(AvailableSettings.MULTI_TENANT, "DATABASE");

        final ServiceRegistry registry = new StandardServiceRegistryBuilder()
                .addService(MultiTenantConnectionProvider.class, connectionProvider)
                .applySettings(configuration.getProperties())
                .build();

        configure(configuration, registry);

        return configuration.buildSessionFactory(registry);
    }

    protected void configure(Configuration configuration, ServiceRegistry registry) {
    }

    private void addAnnotatedClasses(Configuration configuration,
                                     Iterable<Class<?>> entities) {
        final SortedSet<String> entityClasses = new TreeSet<>();
        for (Class<?> klass : entities) {
            configuration.addAnnotatedClass(klass);
            entityClasses.add(klass.getCanonicalName());
        }
        LOGGER.info("Entity classes: {}", entityClasses);
    }
}

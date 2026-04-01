package io.appform.dropwizard.sharding.hibernate;

import io.appform.dropwizard.sharding.config.ShardConfig;
import lombok.experimental.UtilityClass;
import lombok.extern.slf4j.Slf4j;
import org.hibernate.SessionFactory;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
import org.hibernate.cfg.AvailableSettings;
import org.hibernate.cfg.Configuration;
import org.hibernate.service.ServiceRegistry;

import java.util.List;
import java.util.SortedSet;
import java.util.TreeSet;

@Slf4j
@UtilityClass
public class CoreSessionFactoryBuilder {

    public SessionFactory build(final ShardConfig config, final List<Class<?>> entities) {
        final Configuration configuration = new Configuration();

        configuration.setProperty(AvailableSettings.URL, config.getUrl());
        configuration.setProperty(AvailableSettings.DRIVER, config.getDriverClass());
        configuration.setProperty(AvailableSettings.DIALECT, config.getDialect());
        if (config.getUsername() != null) {
            configuration.setProperty(AvailableSettings.USER, config.getUsername());
        }
        if (config.getPassword() != null) {
            configuration.setProperty(AvailableSettings.PASS, config.getPassword());
        }
        configuration.setProperty(AvailableSettings.HBM2DDL_AUTO, config.getHbm2ddl());
        configuration.setProperty(AvailableSettings.CURRENT_SESSION_CONTEXT_CLASS, "managed");
        configuration.setProperty(AvailableSettings.USE_GET_GENERATED_KEYS, "true");
        configuration.setProperty(AvailableSettings.GENERATE_STATISTICS, "true");
        configuration.setProperty(AvailableSettings.USE_REFLECTION_OPTIMIZER, "true");
        configuration.setProperty(AvailableSettings.ORDER_UPDATES, "true");
        configuration.setProperty(AvailableSettings.ORDER_INSERTS, "true");
        configuration.setProperty(AvailableSettings.USE_NEW_ID_GENERATOR_MAPPINGS, "true");
        configuration.setProperty("jadira.usertype.autoRegisterUserTypes", "true");

        if (config.getProperties() != null) {
            config.getProperties().forEach(configuration::setProperty);
        }

        final SortedSet<String> entityClasses = new TreeSet<>();
        for (Class<?> klass : entities) {
            configuration.addAnnotatedClass(klass);
            entityClasses.add(klass.getCanonicalName());
        }
        log.info("Entity classes: {}", entityClasses);

        final ServiceRegistry registry = new StandardServiceRegistryBuilder()
                .applySettings(configuration.getProperties())
                .build();
        return configuration.buildSessionFactory(registry);
    }
}

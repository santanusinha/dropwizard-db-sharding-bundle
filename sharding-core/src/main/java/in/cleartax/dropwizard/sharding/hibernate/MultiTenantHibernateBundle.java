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

package in.cleartax.dropwizard.sharding.hibernate;

import com.fasterxml.jackson.datatype.hibernate5.Hibernate5Module;
import com.fasterxml.jackson.datatype.hibernate5.Hibernate5Module.Feature;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import io.dropwizard.Configuration;
import io.dropwizard.ConfiguredBundle;
import io.dropwizard.setup.Bootstrap;
import io.dropwizard.setup.Environment;
import io.dropwizard.util.Duration;
import org.hibernate.SessionFactory;

public abstract class MultiTenantHibernateBundle<T extends Configuration> implements ConfiguredBundle<T> {

    private static final String DEFAULT_NAME = "hibernate";
    private final ImmutableList<Class<?>> entities;
    private final MultiTenantSessionFactoryFactory sessionFactoryFactory;
    private SessionFactory sessionFactory;
    private boolean lazyLoadingEnabled = true;
    private boolean hibernateHealthCheckRegisterEnabled = false;

    public boolean isHibernateHealthCheckRegisterEnabled() {
        return hibernateHealthCheckRegisterEnabled;
    }

    public void setHibernateHealthCheckRegisterEnabled(boolean hibernateHealthCheckRegisterEnabled) {
        this.hibernateHealthCheckRegisterEnabled = hibernateHealthCheckRegisterEnabled;
    }

    protected MultiTenantHibernateBundle(Class<?> entity, Class<?>... entities) {
        this(ImmutableList.<Class<?>>builder().add(entity).add(entities).build(),
                new MultiTenantSessionFactoryFactory());
    }

    protected MultiTenantHibernateBundle(ImmutableList<Class<?>> entities,
                                         MultiTenantSessionFactoryFactory sessionFactoryFactory) {
        this.entities = entities;
        this.sessionFactoryFactory = sessionFactoryFactory;
    }

    public abstract MultiTenantDataSourceFactory getDataSourceFactory(T configuration);

    @Override
    public final void initialize(Bootstrap<?> bootstrap) {
        bootstrap.getObjectMapper().registerModule(createHibernate5Module());
    }

    /**
     * Override to configure the {@link Hibernate5Module}.
     */
    protected Hibernate5Module createHibernate5Module() {
        Hibernate5Module module = new Hibernate5Module();
        if (lazyLoadingEnabled) {
            module.enable(Feature.FORCE_LAZY_LOADING);
        }
        return module;
    }

    /**
     * Override to configure the name of the bundle
     * (It's used for the bundle health check and database pool metrics)
     */
    protected String name() {
        return DEFAULT_NAME;
    }

    @Override
    public final void run(T configuration, Environment environment) {
        final MultiTenantDataSourceFactory dbConfig = getDataSourceFactory(configuration);
        this.sessionFactory = sessionFactoryFactory.build(this, environment, dbConfig, entities, name());
        if(hibernateHealthCheckRegisterEnabled) {
            environment.healthChecks().register(name(),
                    new MultiTenantSessionFactoryHealthCheck(
                            environment.getHealthCheckExecutorService(),
                            dbConfig.getValidationQueryTimeout().orElse(Duration.seconds(5)),
                            sessionFactory,
                            new MultiTenantUnitOfWorkAwareProxyFactory(this),
                            Lists.newArrayList(dbConfig.getTenantDbMap().keySet()),
                            dbConfig.getValidationQuery()));
        }
    }

    public boolean isLazyLoadingEnabled() {
        return lazyLoadingEnabled;
    }

    public void setLazyLoadingEnabled(boolean lazyLoadingEnabled) {
        this.lazyLoadingEnabled = lazyLoadingEnabled;
    }

    public SessionFactory getSessionFactory() {
        return sessionFactory;
    }

    protected void configure(org.hibernate.cfg.Configuration configuration) {
    }
}

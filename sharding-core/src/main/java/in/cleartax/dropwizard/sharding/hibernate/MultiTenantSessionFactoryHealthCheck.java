/*
 * Copyright 2018 Cleartax
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

import com.codahale.metrics.health.HealthCheck;
import com.google.common.util.concurrent.MoreExecutors;
import in.cleartax.dropwizard.sharding.transactions.DefaultUnitOfWorkImpl;
import in.cleartax.dropwizard.sharding.transactions.TransactionRunner;
import io.dropwizard.db.TimeBoundHealthCheck;
import io.dropwizard.util.Duration;
import org.hibernate.SessionFactory;
import org.hibernate.validator.constraints.NotEmpty;

import java.util.List;
import java.util.concurrent.ExecutorService;

/**
 * Created on 11/10/18
 */
public class MultiTenantSessionFactoryHealthCheck extends HealthCheck {
    @NotEmpty
    private final List<String> tenantIdentifiers;
    private final MultiTenantUnitOfWorkAwareProxyFactory proxyFactory;
    private final SessionFactory sessionFactory;
    private final String validationQuery;
    private final TimeBoundHealthCheck timeBoundHealthCheck;

    public MultiTenantSessionFactoryHealthCheck(SessionFactory sessionFactory,
                                                MultiTenantUnitOfWorkAwareProxyFactory proxyFactory,
                                                List<String> tenantIdentifiers,
                                                String validationQuery) {
        this(MoreExecutors.newDirectExecutorService(), Duration.seconds(0), sessionFactory,
                proxyFactory, tenantIdentifiers, validationQuery);
    }

    public MultiTenantSessionFactoryHealthCheck(ExecutorService executorService,
                                                Duration duration,
                                                SessionFactory sessionFactory,
                                                MultiTenantUnitOfWorkAwareProxyFactory proxyFactory,
                                                List<String> tenantIdentifiers,
                                                String validationQuery) {
        this.sessionFactory = sessionFactory;
        this.validationQuery = validationQuery;
        this.proxyFactory = proxyFactory;
        this.tenantIdentifiers = tenantIdentifiers;
        this.timeBoundHealthCheck = new TimeBoundHealthCheck(executorService, duration);
    }


    public SessionFactory getSessionFactory() {
        return sessionFactory;
    }

    public String getValidationQuery() {
        return validationQuery;
    }

    @Override
    protected Result check() {
        return timeBoundHealthCheck.check(this::checkEachTenant);
    }

    private Result checkEachTenant() {
        try {
            for (String eachTenantId : tenantIdentifiers) {
                new TransactionRunner<Void>(proxyFactory, sessionFactory,
                        new ConstTenantIdentifierResolver(eachTenantId), this.getClass().getEnclosingMethod()) {
                    @Override
                    public Void run() {
                        sessionFactory.getCurrentSession().createNativeQuery(validationQuery).list();
                        return null;
                    }
                }.start(false, new DefaultUnitOfWorkImpl());
            }
        } catch (Throwable t) {
            throw new RuntimeException(t);
        }
        return Result.healthy();
    }
}

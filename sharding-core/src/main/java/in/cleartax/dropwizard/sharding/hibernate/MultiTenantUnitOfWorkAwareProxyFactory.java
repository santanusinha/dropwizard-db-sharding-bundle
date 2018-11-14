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

import com.google.common.collect.ImmutableMap;
import org.hibernate.SessionFactory;

public class MultiTenantUnitOfWorkAwareProxyFactory {
    private final ImmutableMap<String, SessionFactory> sessionFactories;

    public MultiTenantUnitOfWorkAwareProxyFactory(String name, SessionFactory sessionFactory) {
        sessionFactories = ImmutableMap.of(name, sessionFactory);
    }

    public MultiTenantUnitOfWorkAwareProxyFactory(MultiTenantHibernateBundle<?>... bundles) {
        final ImmutableMap.Builder<String, SessionFactory> sessionFactoriesBuilder = ImmutableMap.builder();
        for (MultiTenantHibernateBundle<?> bundle : bundles) {
            sessionFactoriesBuilder.put(bundle.name(), bundle.getSessionFactory());
        }
        sessionFactories = sessionFactoriesBuilder.build();
    }

    /**
     * @return a new aspect
     */
    public MultiTenantUnitOfWorkAspect newAspect() {
        return new MultiTenantUnitOfWorkAspect(sessionFactories);
    }

    /**
     * @param sessionFactories
     * @return a new aspect
     */
    public MultiTenantUnitOfWorkAspect newAspect(ImmutableMap<String, SessionFactory> sessionFactories) {
        return new MultiTenantUnitOfWorkAspect(sessionFactories);
    }
}

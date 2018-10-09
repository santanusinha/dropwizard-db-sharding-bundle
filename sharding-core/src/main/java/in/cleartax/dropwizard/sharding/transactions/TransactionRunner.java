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

package in.cleartax.dropwizard.sharding.transactions;

import in.cleartax.dropwizard.sharding.hibernate.ConstTenantIdentifierResolver;
import in.cleartax.dropwizard.sharding.hibernate.DelegatingTenantResolver;
import in.cleartax.dropwizard.sharding.hibernate.MultiTenantUnitOfWorkAwareProxyFactory;
import io.dropwizard.hibernate.UnitOfWork;
import io.dropwizard.hibernate.UnitOfWorkAspect;
import lombok.AllArgsConstructor;
import org.hibernate.SessionFactory;
import org.hibernate.context.internal.ManagedSessionContext;

@AllArgsConstructor
public abstract class TransactionRunner {
    private MultiTenantUnitOfWorkAwareProxyFactory proxyFactory;
    private SessionFactory sessionFactory;
    private ConstTenantIdentifierResolver tenantIdentifierResolver;

    public Object start(boolean reUseSession, UnitOfWork unitOfWork) throws Throwable {
        if (reUseSession) {
            if (ManagedSessionContext.hasBind(sessionFactory)) {
                return run();
            }
        }
        DelegatingTenantResolver.getInstance().setDelegate(tenantIdentifierResolver);
        UnitOfWorkAspect aspect = proxyFactory.newAspect();
        Exception ex = null;
        Object result = null;
        try {
            aspect.beforeStart(unitOfWork);
            result = run();
            aspect.afterEnd();
        } catch (Exception e) {
            ex = e;
            aspect.onError();
        } finally {
            aspect.onFinish();
            DelegatingTenantResolver.getInstance().setDelegate(null);
        }
        if (ex != null) {
            throw ex;
        }
        return result;
    }

    public abstract Object run() throws Throwable;
}

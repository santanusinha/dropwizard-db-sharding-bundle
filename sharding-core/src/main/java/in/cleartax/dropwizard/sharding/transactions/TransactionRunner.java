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
import in.cleartax.dropwizard.sharding.hibernate.MultiTenantUnitOfWorkAspect;
import in.cleartax.dropwizard.sharding.hibernate.MultiTenantUnitOfWorkAwareProxyFactory;
import io.dropwizard.hibernate.UnitOfWork;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.hibernate.SessionFactory;
import org.hibernate.context.internal.ManagedSessionContext;

@AllArgsConstructor
@Slf4j
public abstract class TransactionRunner<T> {
    private MultiTenantUnitOfWorkAwareProxyFactory proxyFactory;
    private SessionFactory sessionFactory;
    private ConstTenantIdentifierResolver tenantIdentifierResolver;


    public T start(boolean reUseSession, UnitOfWork unitOfWork) throws Throwable {
        return start(reUseSession, unitOfWork, "unnamed-context");
    }


    public T start(boolean reUseSession, UnitOfWork unitOfWork, String context) throws Throwable {
        if (reUseSession && ManagedSessionContext.hasBind(sessionFactory) &&
                tenantIdentifierResolver.resolveCurrentTenantIdentifier()
                        .equals(DelegatingTenantResolver.getInstance().resolveCurrentTenantIdentifier())) {
            return run();
        }
        DelegatingTenantResolver.getInstance().setDelegate(tenantIdentifierResolver);
        MultiTenantUnitOfWorkAspect aspect = proxyFactory.newAspect();
        Exception ex = null;
        T result = null;
        long startTime = System.currentTimeMillis();
        try {
            aspect.beforeStart(unitOfWork);
            result = run();
            aspect.afterEnd();
        } catch (Exception e) {
            ex = e;
            aspect.onError();
        } finally {
            aspect.onFinish();
            log.trace("[DATABASE] transaction={} error={} context={} time-elapsed={}",
                    unitOfWork.transactional(), ex != null, context, System.currentTimeMillis() - startTime);
            DelegatingTenantResolver.getInstance().setDelegate(null);
        }

        if (ex != null) {
            throw ex;
        }
        return result;
    }

    public abstract T run() throws Throwable;
}

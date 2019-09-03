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
import in.cleartax.dropwizard.sharding.transactions.listeners.TransactionRunnerListener;
import io.dropwizard.hibernate.UnitOfWork;
import lombok.AllArgsConstructor;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.aopalliance.intercept.MethodInvocation;
import org.hibernate.SessionFactory;
import org.hibernate.context.internal.ManagedSessionContext;

import javax.annotation.Nullable;
import java.lang.reflect.Method;
import java.util.Objects;

@RequiredArgsConstructor
@Slf4j
public abstract class TransactionRunner<T> {
    private final MultiTenantUnitOfWorkAwareProxyFactory proxyFactory;
    private final SessionFactory sessionFactory;
    private final ConstTenantIdentifierResolver tenantIdentifierResolver;
    private final TransactionContext transactionContext;

    @Nullable
    @Setter
    private TransactionRunnerListener listener;

    public T start(boolean reUseSession, UnitOfWork unitOfWork) throws Throwable {
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
            invokeStartListener(unitOfWork);
            aspect.beforeStart(unitOfWork);
            result = run();
            aspect.afterEnd();
        } catch (Exception e) {
            ex = e;
            aspect.onError();
        } finally {
            aspect.onFinish();
            long timeElapsed = System.currentTimeMillis() - startTime;
            log.trace("[DATABASE] transaction={} error={} context={} time-elapsed={}",
                    unitOfWork.transactional(), ex != null, resolveOperationName(transactionContext.getMethodOfInvocation()), timeElapsed);
            DelegatingTenantResolver.getInstance().setDelegate(null);
            invokeFinishListener(ex != null, unitOfWork, timeElapsed);
        }

        if (ex != null) {
            throw ex;
        }
        return result;
    }

    private void invokeStartListener(UnitOfWork unitOfWork) {
        if (listener != null) {
            listener.onStart(unitOfWork, transactionContext);
        }
    }

    private void invokeFinishListener(boolean success, UnitOfWork unitOfWork, long timeElapsed) {
        if (listener != null) {
            listener.onFinish(success, unitOfWork, transactionContext, timeElapsed);
        }
    }


    private String resolveOperationName(Method method) {
        if (Objects.nonNull(method)) {
            return String.format("%s#%s", method.getDeclaringClass().getSimpleName(), method.getName());
        }
        return null;
    }


    public abstract T run() throws Throwable;


}

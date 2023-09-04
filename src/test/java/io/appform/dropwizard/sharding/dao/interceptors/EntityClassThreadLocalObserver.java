package io.appform.dropwizard.sharding.dao.interceptors;

import io.appform.dropwizard.sharding.execution.TransactionExecutionContext;
import io.appform.dropwizard.sharding.observers.TransactionObserver;
import org.slf4j.MDC;

import java.util.function.Supplier;

public class EntityClassThreadLocalObserver extends TransactionObserver {

    public EntityClassThreadLocalObserver(TransactionObserver next) {
        super(next);
    }

    @Override
    public <T> T execute(TransactionExecutionContext context, Supplier<T> supplier) {
        MDC.put(InterceptorTestUtil.ENTITY_START, context.getEntityClass().getSimpleName());
        try {
            return proceed(context, supplier);
        }
        finally {
            MDC.put(InterceptorTestUtil.ENTITY_END, context.getEntityClass().getSimpleName());
        }
    }
}

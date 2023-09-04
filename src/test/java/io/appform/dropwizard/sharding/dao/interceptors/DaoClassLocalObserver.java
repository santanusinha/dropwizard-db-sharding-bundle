package io.appform.dropwizard.sharding.dao.interceptors;

import io.appform.dropwizard.sharding.execution.TransactionExecutionContext;
import io.appform.dropwizard.sharding.observers.TransactionObserver;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;

import java.util.function.Supplier;

@Slf4j
public class DaoClassLocalObserver extends TransactionObserver {

    public DaoClassLocalObserver(TransactionObserver next) {
        super(next);
    }

    @Override
    public <T> T execute(TransactionExecutionContext context, Supplier<T> supplier) {
        MDC.put(InterceptorTestUtil.DAO_START, context.getDaoClass().getSimpleName());
        try {
            return proceed(context, supplier);
        }
        finally {
            MDC.put(InterceptorTestUtil.DAO_END, context.getDaoClass().getSimpleName());
        }
    }
}

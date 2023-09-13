package io.appform.dropwizard.sharding.dao.interceptors;

import com.google.common.base.Stopwatch;
import io.appform.dropwizard.sharding.execution.TransactionExecutionContext;
import io.appform.dropwizard.sharding.observers.TransactionObserver;
import lombok.extern.slf4j.Slf4j;
import lombok.val;

import java.util.function.Supplier;

/**
 *
 */
@Slf4j
public class TimerObserver extends TransactionObserver {
    public TimerObserver() {
        this(null);
    }

    public TimerObserver(TransactionObserver next) {
        super(next);
    }

    @Override
    public <T> T execute(TransactionExecutionContext context, Supplier<T> supplier) {
        val w = Stopwatch.createStarted();
        try {
            return proceed(context, supplier);
        } finally {
            log.info("Method {}::{} took: {}ms",
                    context.getDaoClass().getSimpleName(),
                    context.getOpType(),
                    w.elapsed());
        }
    }
}

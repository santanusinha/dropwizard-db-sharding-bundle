package io.appform.dropwizard.sharding.observers.internal;

import io.appform.dropwizard.sharding.exceptions.TransactionFilteredException;
import io.appform.dropwizard.sharding.execution.TransactionExecutionContext;
import io.appform.dropwizard.sharding.filters.FilterOutput;
import io.appform.dropwizard.sharding.filters.TransactionFilter;
import io.appform.dropwizard.sharding.filters.TransactionFilterResult;
import io.appform.dropwizard.sharding.observers.TransactionObserver;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import lombok.val;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * Runs all registered filters. Throws {@link TransactionFilteredException} if any of the {@link TransactionFilter}
 * returns {@link FilterOutput#BLOCK}.
 */
@Getter
@Slf4j
public final class FilteringObserver extends TransactionObserver {

    private final List<TransactionFilter> filters = new ArrayList<>();

    public FilteringObserver(TransactionObserver next) {
        super(next);
    }

    public FilteringObserver addFilters(final Collection<TransactionFilter> filters) {
        this.filters.addAll(filters);
        return this;
    }

    @Override
    @SuppressWarnings("java:S1181")
    public final <T> T execute(TransactionExecutionContext context, Supplier<T> supplier) {
        val blocks = filters.stream()
                .map(filter -> {
                    try {
                        return filter.evaluate(context);
                    }
                    catch (Throwable t) {
                        log.error("Error running filter: " + filter.getClass(), t);
                        return TransactionFilterResult.allow();
                    }
                })
                .filter(result -> FilterOutput.BLOCK.equals(result.getOutput()))
                .map(TransactionFilterResult::getReason)
                .collect(Collectors.toList());
        if (blocks.isEmpty()) {
            return proceed(context, supplier);
        }
        throw new TransactionFilteredException(blocks);
    }
}

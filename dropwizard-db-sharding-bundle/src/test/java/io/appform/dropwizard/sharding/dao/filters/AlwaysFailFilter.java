package io.appform.dropwizard.sharding.dao.filters;

import io.appform.dropwizard.sharding.execution.TransactionExecutionContext;
import io.appform.dropwizard.sharding.filters.TransactionFilter;
import io.appform.dropwizard.sharding.filters.TransactionFilterResult;

/**
 *
 */
public class AlwaysFailFilter implements TransactionFilter {
    @Override
    public TransactionFilterResult evaluate(TransactionExecutionContext context) {
        return TransactionFilterResult.block("This always fails");
    }
}

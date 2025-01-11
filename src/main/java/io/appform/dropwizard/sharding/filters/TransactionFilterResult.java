package io.appform.dropwizard.sharding.filters;

import lombok.Value;

/**
 * Output for the filter operation. If {@link FilterOutput#BLOCK} is returned, a reason should be returned
 */
@Value
public class TransactionFilterResult {
    FilterOutput output;
    String reason;

    public static TransactionFilterResult allow() {
        return new TransactionFilterResult(FilterOutput.PROCEED, null);
    }

    public static TransactionFilterResult block(final String reason) {
        return new TransactionFilterResult(FilterOutput.BLOCK, reason);
    }
}

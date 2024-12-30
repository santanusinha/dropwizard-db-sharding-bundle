package io.appform.dropwizard.sharding.exceptions;

import lombok.Getter;

import java.util.Collection;

/**
 * Thrown when a transaction is filtered
 */
@Getter
public class TransactionFilteredException extends RuntimeException {
    private final Collection<String> reasons;

    public TransactionFilteredException(Collection<String> reasons) {
        super("Transaction blocked for the following errors: " + reasons);
        this.reasons = reasons;
    }
}

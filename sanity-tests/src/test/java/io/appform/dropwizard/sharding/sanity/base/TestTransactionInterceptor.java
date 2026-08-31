package io.appform.dropwizard.sharding.sanity.base;

import org.hibernate.EmptyInterceptor;
import org.hibernate.Transaction;

/**
 * Hibernate interceptor that records {@link DbEventTracker.DbEvent#BEGIN_TRANSACTION}
 * when a Hibernate transaction begins.
 */
public class TestTransactionInterceptor extends EmptyInterceptor {

    @Override
    public void afterTransactionBegin(Transaction tx) {
        DbEventTracker.log(DbEventTracker.DbEvent.BEGIN_TRANSACTION);
        super.afterTransactionBegin(tx);
    }
}

package io.appform.dropwizard.sharding.dao;

import org.hibernate.SessionFactory;

interface LockedTransactionContext {
    String getTenantId();

    int getShardId();

    SessionFactory getSessionFactory();
}

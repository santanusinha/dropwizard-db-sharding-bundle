/*
 * Copyright 2016 Santanu Sinha <santanu.sinha@gmail.com>
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

package io.appform.dropwizard.sharding.utils;

import lombok.Getter;
import org.hibernate.CacheMode;
import org.hibernate.FlushMode;
import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.hibernate.Transaction;
import org.hibernate.context.internal.ManagedSessionContext;
import org.hibernate.resource.transaction.spi.TransactionStatus;

/**
 * A transaction handler utility class
 */
public class TransactionHandler {


    // Context variables
    @Getter
    private Session session;
    private final SessionFactory sessionFactory;
    private final boolean readOnly;
    private final boolean skipCommit;

    public TransactionHandler(SessionFactory sessionFactory, boolean readOnly) {
        this(sessionFactory, readOnly, false);
    }

    public TransactionHandler(SessionFactory sessionFactory, boolean readOnly, boolean skipCommit) {
        this.sessionFactory = sessionFactory;
        this.readOnly = readOnly;
        this.skipCommit = skipCommit;
    }

    public void beforeStart() {

        session = sessionFactory.openSession();
        try {
            configureSession();
            ManagedSessionContext.bind(session);
            if (!skipCommit) {
                beginTransaction();
            }
        } catch (Throwable th) {
            session.close();
            session = null;
            ManagedSessionContext.unbind(sessionFactory);
            throw th;
        }
    }

    public void afterEnd() {
        if (session == null) {
            return;
        }

        try {
            if (!skipCommit) {
                commitTransaction();
            }
        } catch (Exception e) {
            if (!skipCommit) {
                rollbackTransaction();
            }
            throw e;
        } finally {
            session.close();
            session = null;
            ManagedSessionContext.unbind(sessionFactory);
        }

    }

    public void onError() {
        if (session == null) {
            return;
        }

        try {
            rollbackTransaction();
        } finally {
            session.close();
            session = null;
            ManagedSessionContext.unbind(sessionFactory);
        }
    }

    private void configureSession() {
        session.setDefaultReadOnly(readOnly);
        session.setCacheMode(CacheMode.NORMAL);
        session.setHibernateFlushMode(FlushMode.AUTO);
    }

    private void beginTransaction() {
        session.beginTransaction();
    }

    private void rollbackTransaction() {
        final Transaction txn = session.getTransaction();
        if (txn != null && txn.getStatus() == TransactionStatus.ACTIVE) {
            txn.rollback();
        }
    }

    private void commitTransaction() {
        final Transaction txn = session.getTransaction();
        if (txn != null && txn.getStatus() == TransactionStatus.ACTIVE) {
            txn.commit();
        }
    }
}

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
import org.jboss.logging.MDC;

/**
 * A transaction handler utility class
 */
public class TransactionHandler {


    public static final String TENANT_ID = "tenant.id";
    // Context variables
    @Getter
    private Session session;
    private final SessionFactory sessionFactory;
    private final boolean readOnly;
    private boolean skipCommit;
    private boolean sessionAcquired = false;

    public TransactionHandler(SessionFactory sessionFactory, boolean readOnly) {
        this(sessionFactory, readOnly, false);
    }

    public TransactionHandler(SessionFactory sessionFactory, boolean readOnly, boolean skipCommit) {
        this.sessionFactory = sessionFactory;
        this.readOnly = readOnly;
        this.skipCommit = skipCommit;
    }

    /**
     * Prepares the transactional context before executing a unit of work.
     * <p>
     * This method implements the core logic for the Unit of Work pattern, providing support
     * for nested transactions. It first checks if a Hibernate {@link Session} is already bound
     * to the current thread using {@link ManagedSessionContext}.
     * <ul>
     * <li><b>If a session exists:</b> It is reused. The handler checks if a transaction is
     * already active and updates its internal state to skip the final commit, thereby "joining"
     * the existing transaction.</li>
     * <li><b>If no session exists:</b> A new session is opened, configured, and bound to the
     * context. This handler instance then takes "ownership" of the session by setting the
     * {@code sessionAcquired} flag, becoming responsible for its closure and transaction completion.
     * </li>
     * </ul>
     * <p>
     * If this handler is determined to be the owner of the transaction, it begins one after the
     * session is successfully set up. In case of any setup failure, it also ensures proper cleanup.
     *
     */
    public void beforeStart() {
        try {
            if (ManagedSessionContext.hasBind(sessionFactory)) {
                session = sessionFactory.getCurrentSession();
                final var existingTransaction = session.getTransaction();
                skipCommit = skipCommit
                        || (existingTransaction != null && existingTransaction.isActive());
            }
            else {
                session = sessionFactory.openSession();
                configureSession();
                ManagedSessionContext.bind(session);
                sessionAcquired = true;
            }
            if (!skipCommit) {
                beginTransaction();
            }
        } catch (Throwable th) {
            // Only the owner handler instance to acquire the session, will close it.
            if (sessionAcquired) {
                session.close();
                ManagedSessionContext.unbind(sessionFactory);
                MDC.remove(TENANT_ID);
            }
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
            if (sessionAcquired) {
                session.close();
                ManagedSessionContext.unbind(sessionFactory);
                MDC.remove(TENANT_ID);
            }
        }
    }

    public void onError() {
        if (session == null) {
            return;
        }
        try {
            if (!skipCommit) {
                rollbackTransaction();
            }
        } finally {
            if (sessionAcquired) {
                session.close();
                ManagedSessionContext.unbind(sessionFactory);
                MDC.remove(TENANT_ID);
            }
        }
    }

    private void configureSession() {
        session.setDefaultReadOnly(readOnly);
        session.setCacheMode(CacheMode.NORMAL);
        session.setHibernateFlushMode(FlushMode.AUTO);
        //If the bundle is initialized in multi-tenant mode, each session factory is tagged to
        //a tenant id. It will be used in encryption support to fetch the appropriate encryptor for the tenant.
        if(sessionFactory.getProperties().containsKey(TENANT_ID)) {
            MDC.put(TENANT_ID, sessionFactory.getProperties().get(TENANT_ID).toString());
        }
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
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

import io.appform.dropwizard.sharding.ShardInfoProvider;
import io.appform.dropwizard.sharding.listeners.TransactionListenerContext;
import io.appform.dropwizard.sharding.listeners.TransactionListenerExecutor;
import io.appform.dropwizard.sharding.listeners.TransactionListenerFactory;
import lombok.val;
import org.hibernate.Session;
import org.hibernate.SessionFactory;

import java.util.List;
import java.util.Optional;
import java.util.function.Function;

/**
 * Utility functional class for running transactions.
 */
public class TransactionExecutor {

    private final Class<?> daoClass;
    private final Class<?> entityClass;
    private final ShardInfoProvider shardInfoProvider;
    private final TransactionListenerExecutor transactionListenerExecutor;

    public TransactionExecutor(final ShardInfoProvider shardInfoProvider,
                               final Class<?> daoClass,
                               final Class<?> entityClass,
                               final List<TransactionListenerFactory> transactionListenerFactories) {
        this.daoClass = daoClass;
        this.entityClass = entityClass;
        this.shardInfoProvider = shardInfoProvider;
        this.transactionListenerExecutor = new TransactionListenerExecutor(transactionListenerFactories);
    }

    public <T, U> Optional<T> executeAndResolve(SessionFactory sessionFactory, Function<U, T> function, U arg,
                                                String opType,
                                                int shardId) {
        return executeAndResolve(sessionFactory, false, function, arg, opType, shardId);
    }

    public <T, U> Optional<T> executeAndResolve(SessionFactory sessionFactory, boolean readOnly, Function<U, T> function, U arg,
                                                String opType,
                                                int shardId) {
        T result = execute(sessionFactory, readOnly, function, arg, opType, shardId);
        return Optional.ofNullable(result);
    }

    public <T, U> T execute(SessionFactory sessionFactory, boolean readOnly, Function<U, T> function, U arg,
                            String opType,
                            int shardId) {
        return execute(sessionFactory, readOnly, function, arg, t -> t, opType, shardId);
    }

    public <T, U> T execute(SessionFactory sessionFactory, boolean readOnly, Function<U, T> function, U arg, boolean completeTransaction,
                            String opType,
                            int shardId) {
        return execute(sessionFactory, readOnly, function, arg, t -> t, completeTransaction, opType, shardId);
    }

    public <T, U, V> V execute(SessionFactory sessionFactory, boolean readOnly, Function<U, T> function, U arg, Function<T, V> handler,
                               String opType,
                               int shardId) {
        return execute(sessionFactory, readOnly, function, arg, handler, true, opType, shardId);
    }

    public <T, U, V> V execute(SessionFactory sessionFactory,
                               boolean readOnly,
                               Function<U, T> function,
                               U arg,
                               Function<T, V> handler,
                               boolean completeTransaction,
                               String opType,
                               int shardId) {
        val listenerContext = TransactionListenerContext.builder()
                .entityClass(entityClass)
                .daoClass(daoClass)
                .opType(opType)
                .shardName(shardInfoProvider.shardName(shardId))
                .build();
        val listeners = transactionListenerExecutor.createShardedDaoListeners(listenerContext);
        transactionListenerExecutor.beforeExecute(listeners, listenerContext);
        val transactionHandler = new TransactionHandler(sessionFactory, readOnly);
        if (completeTransaction) {
            transactionHandler.beforeStart();
        }
        try {
            T result = function.apply(arg);
            V returnValue = handler.apply(result);
            if (completeTransaction) {
                transactionHandler.afterEnd();
            }
            transactionListenerExecutor.afterExecute(listeners, listenerContext);
            return returnValue;
        } catch (Exception e) {
            if (completeTransaction) {
                transactionHandler.onError();
            }
            transactionListenerExecutor.afterException(listeners, listenerContext, e);
            throw e;
        }
    }

    public <T> T execute(SessionFactory sessionFactory, Function<Session, T> handler, String opType, int shardId) {
        val listenerContext = TransactionListenerContext.builder()
                .entityClass(entityClass)
                .daoClass(daoClass)
                .opType(opType)
                .shardName(shardInfoProvider.shardName(shardId))
                .build();
        val listeners = transactionListenerExecutor.createShardedDaoListeners(listenerContext);
        transactionListenerExecutor.beforeExecute(listeners, listenerContext);
        val transactionHandler = new TransactionHandler(sessionFactory, true);
        transactionHandler.beforeStart();
        try {
            T result = handler.apply(transactionHandler.getSession());
            transactionHandler.afterEnd();
            transactionListenerExecutor.afterExecute(listeners, listenerContext);
            return result;
        } catch (Exception e) {
            transactionHandler.onError();
            transactionListenerExecutor.afterException(listeners, listenerContext, e);
            throw e;
        }
    }
}

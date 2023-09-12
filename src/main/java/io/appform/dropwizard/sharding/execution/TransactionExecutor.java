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

package io.appform.dropwizard.sharding.execution;

import io.appform.dropwizard.sharding.ShardInfoProvider;
import io.appform.dropwizard.sharding.observers.TransactionObserver;
import io.appform.dropwizard.sharding.utils.TransactionHandler;
import lombok.val;
import org.hibernate.Session;
import org.hibernate.SessionFactory;

import java.util.Optional;
import java.util.function.Function;

/**
 * Utility functional class for running transactions.
 */
public class TransactionExecutor {

    private final Class<?> daoClass;
    private final Class<?> entityClass;
    private final ShardInfoProvider shardInfoProvider;
    private final TransactionObserver observer;

    public TransactionExecutor(final ShardInfoProvider shardInfoProvider,
                               final Class<?> daoClass,
                               final Class<?> entityClass,
                               final TransactionObserver observer) {
        this.daoClass = daoClass;
        this.entityClass = entityClass;
        this.shardInfoProvider = shardInfoProvider;
        this.observer = observer;
    }

    public <T, U> Optional<T> executeAndResolve(SessionFactory sessionFactory, boolean readOnly, Function<U, T> function, U arg,
                                                String opType,
                                                int shardId, boolean skipCommit) {
        T result = execute(sessionFactory, readOnly, function, arg, opType, shardId, skipCommit);
        return Optional.ofNullable(result);
    }

    public <T, U> T execute(SessionFactory sessionFactory, boolean readOnly, Function<U, T> function, U arg,
                            String opType,
                            int shardId,
                            boolean skipCommit) {
        return execute(sessionFactory, readOnly, function, arg, t -> t, opType, shardId, skipCommit);
    }

    public <T, U> T execute(SessionFactory sessionFactory, boolean readOnly, Function<U, T> function, U arg, boolean completeTransaction,
                            String opType,
                            int shardId, boolean skipCommit) {
        return execute(sessionFactory, readOnly, function, arg, t -> t, completeTransaction, opType, shardId, skipCommit);
    }

    public <T, U, V> V execute(SessionFactory sessionFactory, boolean readOnly, Function<U, T> function, U arg, Function<T, V> handler,
                               String opType,
                               int shardId, boolean skipCommit) {
        return execute(sessionFactory, readOnly, function, arg, handler, true, opType, shardId, skipCommit);
    }

    public <T, U, V> V execute(SessionFactory sessionFactory,
                               boolean readOnly,
                               Function<U, T> function,
                               U arg,
                               Function<T, V> handler,
                               boolean completeTransaction,
                               String opType,
                               int shardId,
                               boolean skipCommit) {
        return execute(sessionFactory,
                readOnly,
                session -> {
                    T result = function.apply(arg);
                    return handler.apply(result);
                },
                completeTransaction,
                opType,
                shardId,
                skipCommit
            );
    }

    public <T> T execute(
            SessionFactory sessionFactory,
            boolean readOnly,
            Function<Session, T> handler,
            boolean completeTransaction,
            String opType,
            int shardId,
            boolean skipCommit) {
        val context = TransactionExecutionContext.builder()
                .daoClass(daoClass)
                .entityClass(entityClass)
                .shardName(shardInfoProvider.shardName(shardId))
                .opType(opType)
                .build();
        return observer.execute(context, () -> {
            val transactionHandler = new TransactionHandler(sessionFactory, readOnly, skipCommit);
            if (completeTransaction) {
                transactionHandler.beforeStart();
            }
            try {
                T result = handler.apply(transactionHandler.getSession());
                if (completeTransaction) {
                    transactionHandler.afterEnd();
                }
                return result;
            } catch (Exception e) {
                if (completeTransaction) {
                    transactionHandler.onError();
                }
                throw e;
            }
        });
    }
}

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
import io.appform.dropwizard.sharding.dao.operations.OpContext;
import io.appform.dropwizard.sharding.observers.TransactionObserver;
import io.appform.dropwizard.sharding.utils.TransactionHandler;
import java.util.Optional;
import java.util.function.Function;
import lombok.val;
import org.hibernate.Session;
import org.hibernate.SessionFactory;

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

    public <T> T execute(SessionFactory sessionFactory,
        boolean readOnly,
        String commandName,
        OpContext<T> opContext,
        int shardId) {
        return execute(sessionFactory, readOnly, commandName, opContext, shardId, true);
    }

    public <T> T execute(SessionFactory sessionFactory,
        boolean readOnly,
        String commandName,
        OpContext<T> opContext,
        int shardId,
        boolean completeTransaction) {
        val context = TransactionExecutionContext.builder()
            .commandName(commandName)
            .daoClass(daoClass)
            .entityClass(entityClass)
            .shardName(shardInfoProvider.shardName(shardId))
            .opContext(opContext)
            .build();
        return observer.execute(context, () -> {
            val transactionHandler = new TransactionHandler(sessionFactory, readOnly);
            if (completeTransaction) {
                transactionHandler.beforeStart();
            }
            try {
                T result = opContext.apply(transactionHandler.getSession());
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

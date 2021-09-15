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

import com.codahale.metrics.Timer;
import com.codahale.metrics.MetricRegistry;
import org.hibernate.Session;
import org.hibernate.SessionFactory;

import java.util.Optional;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Utility functional class for running transactions.
 */
public class Transactions {
    private Transactions() {}

    public static <T, U> Optional<T> executeAndResolve(SessionFactory sessionFactory, Function<U, T> function, U arg) {
        return executeAndResolve(sessionFactory, false, function, arg);
    }

    public static <T, U> Optional<T> executeAndResolve(SessionFactory sessionFactory, boolean readOnly, Function<U, T> function, U arg) {
        T result = execute(sessionFactory, readOnly, function, arg);
        return Optional.ofNullable(result);
    }

    public static <T, U> T execute(SessionFactory sessionFactory, boolean readOnly, Function<U, T> function, U arg) {
        return execute(sessionFactory, readOnly, function, arg, t -> t);
    }

    public static <T, U> T execute(SessionFactory sessionFactory, boolean readOnly, Function<U, T> function, U arg, boolean completeTransaction) {
        return execute(sessionFactory, readOnly, function, arg, t -> t, completeTransaction);
    }

    public static <T, U, V> V execute(SessionFactory sessionFactory, boolean readOnly, Function<U, T> function, U arg, Function<T, V> handler) {
        return execute(sessionFactory, readOnly, function, arg, handler, true);
    }

    public static <T, U, V> V execute(SessionFactory sessionFactory, boolean readOnly, Function<U, T> function, U arg, Function<T, V> handler, boolean completeTransaction) {
        TransactionHandler transactionHandler = new TransactionHandler(sessionFactory, readOnly);
        if(completeTransaction) {
            transactionHandler.beforeStart();
        }
        try {
            T result = function.apply(arg);
            V returnValue = handler.apply(result);
            if(completeTransaction) {
                transactionHandler.afterEnd();
            }
            return returnValue;
        } catch (Exception e) {
            if(completeTransaction) {
                transactionHandler.onError();
            }
            throw e;
        }
    }

    public static <T> T execute(SessionFactory sessionFactory, Function<Session, T> handler) {
        TransactionHandler transactionHandler = new TransactionHandler(sessionFactory, true);
        transactionHandler.beforeStart();
        try {
            T result = handler.apply(transactionHandler.getSession());
            transactionHandler.afterEnd();
            return result;
        } catch (Exception e) {
            transactionHandler.onError();
            throw e;
        }
    }

    public static <T> T executeTracked(MetricRegistry metricRegistry, Supplier<T> t, String metricName){
        Timer.Context time = metricRegistry.timer(metricName).time();
        try {
            return t.get();
        }
        catch (Exception e){
            metricRegistry.meter(metricName + ".exceptions").mark();
            throw e;
        }
        finally {
            time.stop();
        }
    }
}

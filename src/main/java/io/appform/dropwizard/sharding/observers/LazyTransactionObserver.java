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

package io.appform.dropwizard.sharding.observers;

import com.google.common.base.Preconditions;
import io.appform.dropwizard.sharding.execution.TransactionExecutionContext;

import java.util.function.Supplier;

/**
 * A {@link TransactionObserver} that defers resolution of the real observer chain to the moment
 * {@link #execute(TransactionExecutionContext, Supplier)} is actually invoked, rather than
 * capturing it once at construction time.
 * <p>
 * <b>Why this exists:</b> {@code MultiTenantDBShardingBundleBase} builds a brand new observer
 * chain per tenant inside {@code run()}, by <i>reassigning</i> its {@code rootObserver} field
 * wholesale (e.g. {@code rootObserver = new FilteringObserver(rootObserver)...}), rather than
 * mutating an existing chain object in place. DAOs are handed this reference directly at
 * construction time. If a DAO is constructed before {@code run()} has executed - for example,
 * by a DI container (Guice, Spring) that eagerly builds {@code @Provides} singletons before the
 * bundle's own {@code run()} lifecycle method fires - the DAO permanently captures whatever
 * {@code rootObserver} was <i>at that moment</i>, which is {@code null} before any tenant has been
 * set up. Because {@code rootObserver} is later reassigned (not mutated in place, unlike the
 * sharding bundle's {@code Map} fields), there is no way for the DAO's already-captured reference
 * to ever observe that reassignment.
 * <p>
 * This class breaks that dependency: the bundle constructs exactly one {@code LazyTransactionObserver}
 * instance up front and hands <i>that stable object</i> to every DAO. The bundle then calls
 * {@link #set(TransactionObserver)} on it whenever the real chain is (re)built. Every DAO operation
 * resolves the current delegate lazily, at call time - by which point {@code run()} has completed.
 */
public final class LazyTransactionObserver extends TransactionObserver {

    private volatile TransactionObserver delegate;

    public LazyTransactionObserver() {
        super(null);
    }

    /**
     * Called by the bundle whenever the real observer chain is (re)built.
     */
    public void set(final TransactionObserver delegate) {
        this.delegate = delegate;
    }

    @Override
    public <T> T execute(final TransactionExecutionContext context, final Supplier<T> supplier) {
        final TransactionObserver current = this.delegate;
        Preconditions.checkState(current != null,
                "Observer chain has not been initialised yet. Ensure the bundle's run() has "
                        + "completed before invoking DAO operations.");
        return current.execute(context, supplier);
    }
}

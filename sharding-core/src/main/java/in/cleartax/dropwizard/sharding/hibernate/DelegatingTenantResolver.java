/*
 * Copyright 2018 Saurabh Agrawal (Cleartax)
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

package in.cleartax.dropwizard.sharding.hibernate;

import in.cleartax.dropwizard.sharding.utils.exception.Preconditions;
import org.hibernate.context.spi.CurrentTenantIdentifierResolver;

import java.util.Stack;

public class DelegatingTenantResolver implements CurrentTenantIdentifierResolver {

    private static DelegatingTenantResolver instance;

    private ThreadLocal<Stack<CurrentTenantIdentifierResolver>> delegate =
            ThreadLocal.withInitial(Stack::new);

    private DelegatingTenantResolver() {

    }

    public static DelegatingTenantResolver getInstance() {
        if (instance == null) {
            synchronized (DelegatingTenantResolver.class) {
                if (instance == null) {
                    instance = new DelegatingTenantResolver();
                }
            }
        }
        return instance;
    }

    public void setDelegate(CurrentTenantIdentifierResolver resolver) {
        if (resolver != null) {
            delegate.get().add(resolver);
        } else {
            delegate.get().pop();
        }
    }

    @SuppressWarnings("unused")
    public boolean hasTenantIdentifier() {
        return !delegate.get().isEmpty();
    }

    @Override
    public String resolveCurrentTenantIdentifier() {
        Preconditions.checkArgument(!delegate.get().isEmpty(), "Did you forget to set tenantId");
        return delegate.get().peek().resolveCurrentTenantIdentifier();
    }

    @Override
    public boolean validateExistingCurrentSessions() {
        Preconditions.checkArgument(!delegate.get().isEmpty(), "Did you forget to set tenantId");
        return delegate.get().peek().validateExistingCurrentSessions();
    }
}

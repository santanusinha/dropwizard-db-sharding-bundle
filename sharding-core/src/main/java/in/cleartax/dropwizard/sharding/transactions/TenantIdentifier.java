/*
 * Copyright 2018 Cleartax
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

package in.cleartax.dropwizard.sharding.transactions;

import in.cleartax.dropwizard.sharding.hibernate.MultiTenantDataSourceFactory;
import in.cleartax.dropwizard.sharding.hibernate.MultiTenantSessionSource;
import io.dropwizard.hibernate.UnitOfWork;

import java.lang.annotation.*;

/**
 * Force {@link UnitOfWork} to open session on the specified tenant
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface TenantIdentifier {
    /**
     * If {@code true}, then {@link UnitOfWorkModule} will use the tenantId mentioned in
     * {@link MultiTenantSessionSource#dataSourceFactory}.{@link MultiTenantDataSourceFactory#defaultTenant}
     */
    boolean useDefault();

    /**
     * If {@link #useDefault()}, is false, then {@link UnitOfWorkModule} will read the
     * tenantIdentifier mentioned
     */
    String tenantIdentifier() default "";
}

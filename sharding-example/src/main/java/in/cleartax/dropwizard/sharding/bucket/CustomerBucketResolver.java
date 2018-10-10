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

package in.cleartax.dropwizard.sharding.bucket;

import in.cleartax.dropwizard.sharding.resolvers.bucket.BucketResolver;
import in.cleartax.dropwizard.sharding.dao.CustomerToBucketMappingDAO;
import in.cleartax.dropwizard.sharding.transactions.DefaultTenant;
import io.dropwizard.hibernate.UnitOfWork;
import lombok.RequiredArgsConstructor;

import javax.inject.Inject;
import java.util.Optional;

@RequiredArgsConstructor(onConstructor = @__(@Inject))
public class CustomerBucketResolver implements BucketResolver {

    private final CustomerToBucketMappingDAO dao;

    @Override
    @UnitOfWork
    @DefaultTenant
    public String resolve(String customerId) {
        Optional<String> bucketId = dao.getBucketId(customerId);
        if (!bucketId.isPresent()) {
            throw new IllegalAccessError(String.format("%s customer not mapped to any bucket", customerId));
        }
        return bucketId.get();
    }
}

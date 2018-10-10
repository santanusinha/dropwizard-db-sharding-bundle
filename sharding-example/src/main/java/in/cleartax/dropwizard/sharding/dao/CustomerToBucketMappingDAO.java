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

package in.cleartax.dropwizard.sharding.dao;

import in.cleartax.dropwizard.sharding.entities.CustomerToBucketMapping;
import io.dropwizard.hibernate.AbstractDAO;
import org.hibernate.Criteria;
import org.hibernate.SessionFactory;
import org.hibernate.criterion.Restrictions;

import javax.inject.Inject;
import javax.inject.Named;
import java.util.Optional;

public class CustomerToBucketMappingDAO extends AbstractDAO<CustomerToBucketMapping> {

    private static final String CUSTOMER_ID = "customerId";

    /**
     * Creates a new DAO with a given session provider.
     *
     * @param sessionFactory a session provider
     */
    @Inject
    public CustomerToBucketMappingDAO(@Named("session") SessionFactory sessionFactory) {
        super(sessionFactory);
    }

    // TODO : Replace with criteriaBuilder()
    public Optional<String> getBucketId(String customerId) {
        Criteria criteria = criteria()
                .add(Restrictions.eq(CUSTOMER_ID, customerId));
        CustomerToBucketMapping mapping = (CustomerToBucketMapping) criteria.uniqueResult();
        return Optional.ofNullable(mapping).map(CustomerToBucketMapping::getBucketId);
    }
}

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

package io.appform.dropwizard.sharding.dao.testdata;

import io.appform.dropwizard.sharding.dao.AbstractDAO;
import io.appform.dropwizard.sharding.dao.testdata.entities.Order;
import io.appform.dropwizard.sharding.sharding.ShardedTransaction;
import org.hibernate.SessionFactory;

public class OrderDao extends AbstractDAO<Order> {
    /**
     * Creates a new DAO with a given session provider.
     *
     * @param sessionFactory a session provider
     */
    public OrderDao(SessionFactory sessionFactory) {
        super(sessionFactory);
    }

    @ShardedTransaction
    public Order get(long id) {
        return super.get(id);
    }

    @ShardedTransaction
    public Order save(Order order) {
        return persist(order);
    }
}

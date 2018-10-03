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

package io.dropwizard.sharding.test.testdata.dao;

import io.dropwizard.hibernate.AbstractDAO;
import io.dropwizard.sharding.test.testdata.entities.Order;
import org.hibernate.SessionFactory;

import javax.inject.Inject;
import javax.inject.Named;

public class OrderDao extends AbstractDAO<Order> {
    /**
     * Creates a new DAO with a given session provider.
     *
     * @param sessionFactory a session provider
     */
    @Inject
    public OrderDao(@Named("session") SessionFactory sessionFactory) {
        super(sessionFactory);
    }

    public Order get(long id) {
        return super.get(id);
    }

    public Order save(Order order) {
        order.getItems().forEach(oi -> {
            oi.setOrder(order);
        });
        return persist(order);
    }
}

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

package in.cleartax.dropwizard.sharding.application;

import com.codahale.metrics.annotation.ExceptionMetered;
import com.codahale.metrics.annotation.Timed;
import in.cleartax.dropwizard.sharding.dao.OrderDao;
import in.cleartax.dropwizard.sharding.dto.OrderDto;
import in.cleartax.dropwizard.sharding.dto.OrderMapper;
import in.cleartax.dropwizard.sharding.entities.Order;
import in.cleartax.dropwizard.sharding.services.CustomerService;
import in.cleartax.dropwizard.sharding.services.OrderService;
import in.cleartax.dropwizard.sharding.transactions.ReadOnlyTenant;
import in.cleartax.dropwizard.sharding.transactions.TenantIdentifier;
import io.dropwizard.hibernate.UnitOfWork;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.hibernate.JDBCException;

import javax.annotation.security.PermitAll;
import javax.inject.Inject;
import javax.inject.Singleton;
import javax.validation.constraints.NotNull;
import javax.ws.rs.*;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;

@Path("v0.1/orders")
@Produces(value = {MediaType.APPLICATION_JSON})
@RequiredArgsConstructor(onConstructor = @__(@Inject))
@Slf4j
@Singleton
public class TestResource {
    private final OrderService orderService;
    private final CustomerService customerService;

    // Not recommended. Since this a demo app, injecting it, to test auto-flush
    private final OrderDao orderDao;
    private final OrderMapper orderMapper = new OrderMapper();

    @PUT
    @Timed
    @ExceptionMetered
    @Path("")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    @PermitAll
    @UnitOfWork // Deliberately adding this here to test that,
    // @ReuseSession doesn't re-use session as user lives on default shard and order is on a different shard
    public OrderDto createOrUpdateOrder(@NotNull OrderDto order) {
        if (!customerService.isValidUser(order.getCustomerId())) {
            throw new IllegalAccessError("Unrecognized user");
        }
        return orderService.createOrder(order);
    }

    @PUT
    @Timed
    @ExceptionMetered
    @Path("/replica")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    @PermitAll
    @UnitOfWork
    @ReadOnlyTenant
    public OrderDto createOrUpdateOrderOnReplica(@NotNull OrderDto order) {
        if (!customerService.isValidUser(order.getCustomerId())) {
            throw new IllegalAccessError("Unrecognized user");
        }
        try {
            return orderService.createOrder(order);
        } catch (JDBCException e) {
            if(e.getSQLException().getMessage().contains("The database is read only")) {
                throw new WebApplicationException(Response.Status.FORBIDDEN);
            }
            throw new WebApplicationException(Response.Status.INTERNAL_SERVER_ERROR);
        }
    }

    @GET
    @Timed
    @ExceptionMetered
    @Path("{id}")
    @Produces(MediaType.APPLICATION_JSON)
    @PermitAll
    @UnitOfWork
    public OrderDto getOrder(@PathParam("id") long id) {
        return orderService.getOrder(id);
    }

    @POST
    @Timed
    @ExceptionMetered
    @Path("auto-flush-test")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    @PermitAll
    @UnitOfWork
    // No call will be made to orderDao.createOrUpdate. Hibernate's auto-flush must get triggered
    public OrderDto updateOrderAutoFlush(@NotNull OrderDto order) {
        Order orderEntity = orderDao.get(order.getId());
        orderEntity = orderMapper.updateAmount(orderEntity, order);
        return orderMapper.to(orderEntity);
    }

    @GET
    @Timed
    @ExceptionMetered
    @Path("{id}/shard1")
    @Produces(MediaType.APPLICATION_JSON)
    @PermitAll
    @UnitOfWork
    @TenantIdentifier(useDefault = false, tenantIdentifier = "shard1")
    // Test API which only reads from shard1. Don't follow this pattern on production
    public OrderDto getOrderFromShard1(@PathParam("id") long id) {
        return orderService.getOrder(id);
    }

    @GET
    @Timed
    @ExceptionMetered
    @Path("{id}/shard2")
    @Produces(MediaType.APPLICATION_JSON)
    @PermitAll
    @UnitOfWork
    @TenantIdentifier(useDefault = false, tenantIdentifier = "shard2")
    // Test API which only reads from shard2. Don't follow this pattern on production
    public OrderDto getOrderFromShard2(@PathParam("id") long id) {
        return orderService.getOrder(id);
    }

    @GET
    @Timed
    @ExceptionMetered
    @Path("/replica/{id}")
    @Produces(MediaType.APPLICATION_JSON)
    @PermitAll
    @UnitOfWork
    @ReadOnlyTenant
    // Test API which only reads from readReplica. Don't follow this pattern on production
    public OrderDto getOrderFromReadReplica(@PathParam("id") long id) {
        return orderService.getOrder(id);
    }
}

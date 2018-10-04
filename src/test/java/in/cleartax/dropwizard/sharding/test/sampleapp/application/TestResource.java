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

package in.cleartax.dropwizard.sharding.test.sampleapp.application;

import com.codahale.metrics.annotation.ExceptionMetered;
import com.codahale.metrics.annotation.Timed;
import in.cleartax.dropwizard.sharding.test.sampleapp.testdata.dto.OrderDto;
import in.cleartax.dropwizard.sharding.test.sampleapp.testdata.services.OrderService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import javax.annotation.security.PermitAll;
import javax.inject.Inject;
import javax.inject.Singleton;
import javax.validation.constraints.NotNull;
import javax.ws.rs.*;
import javax.ws.rs.core.MediaType;

@Path("v0.1/orders")
@Produces(value = {MediaType.APPLICATION_JSON})
@RequiredArgsConstructor(onConstructor = @__(@Inject))
@Slf4j
@Singleton
public class TestResource {
    private final OrderService orderService;

    @PUT
    @Timed
    @ExceptionMetered
    @Path("")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    @PermitAll
    public OrderDto createOrUpdateInvoice(@NotNull OrderDto order) {
        return orderService.createOrder(order);
    }

    @GET
    @Timed
    @ExceptionMetered
    @Path("{id}")
    @Produces(MediaType.APPLICATION_JSON)
    @PermitAll
    public OrderDto getOrder(@PathParam("id") long id) {
        return orderService.getOrder(id);
    }
}

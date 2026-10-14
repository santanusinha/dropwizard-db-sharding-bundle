/*
 * Copyright 2026 Santanu Sinha <santanu.sinha@gmail.com>
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

package io.appform.dropwizard.sharding.e2e;

import io.appform.dropwizard.sharding.dao.LookupDao;
import io.appform.dropwizard.sharding.dao.testdata.entities.TestEntity;
import jakarta.validation.constraints.NotEmpty;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import lombok.RequiredArgsConstructor;

/**
 * REST resource writing/reading through the sharded {@link LookupDao} so e2e
 * tests can exercise the bundle over a real HTTP request path.
 */
@Path("/e2e")
@Produces(MediaType.APPLICATION_JSON)
@RequiredArgsConstructor
public class E2ETestResource {

    private final LookupDao<TestEntity> dao;

    @PUT
    @Path("/{key}")
    public Response save(@PathParam("key") String key, String text) throws Exception {
        final var saved = dao.save(TestEntity.builder().externalId(key).text(text).build());
        return saved
                .map(e -> Response.ok(e.getText()).build())
                .orElseGet(() -> Response.serverError().build());
    }

    @GET
    @Path("/{key}")
    public Response get(@PathParam("key") @NotEmpty String key) throws Exception {
        return dao.get(key)
                .map(e -> Response.ok(e.getText()).build())
                .orElseGet(() -> Response.status(Response.Status.NOT_FOUND).build());
    }
}

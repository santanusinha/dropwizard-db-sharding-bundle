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

import in.cleartax.dropwizard.sharding.providers.ShardKeyProvider;
import lombok.RequiredArgsConstructor;

import javax.inject.Inject;
import javax.ws.rs.container.DynamicFeature;
import javax.ws.rs.container.ResourceInfo;
import javax.ws.rs.core.FeatureContext;
import javax.ws.rs.ext.Provider;

@Provider
@RequiredArgsConstructor(onConstructor = @__(@Inject))
public class ShardKeyFeature implements DynamicFeature {

    private final ShardKeyProvider shardKeyProvider;

    @Override
    public void configure(ResourceInfo resourceInfo, FeatureContext context) {
        context.register(new ShardKeyFilter(shardKeyProvider));
    }
}

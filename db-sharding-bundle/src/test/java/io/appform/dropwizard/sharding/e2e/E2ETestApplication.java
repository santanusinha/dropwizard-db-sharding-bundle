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

import io.appform.dropwizard.sharding.DBShardingBundle;
import io.appform.dropwizard.sharding.config.ShardedHibernateFactory;
import io.appform.dropwizard.sharding.dao.LookupDao;
import io.appform.dropwizard.sharding.dao.testdata.entities.TestEntity;
import io.dropwizard.core.Application;
import io.dropwizard.core.setup.Bootstrap;
import io.dropwizard.core.setup.Environment;
import lombok.Getter;

/**
 * Minimal real application used by e2e tests. Boots the full Dropwizard stack
 * (Jetty, Jackson config parsing, validation) with the sharding bundle and one
 * resource that writes/reads through a {@link LookupDao}.
 */
public class E2ETestApplication extends Application<E2ETestConfig> {

    @Getter
    private DBShardingBundle<E2ETestConfig> bundle;

    @Getter
    private LookupDao<TestEntity> dao;

    @Override
    public void initialize(Bootstrap<E2ETestConfig> bootstrap) {
        bundle = new DBShardingBundle<E2ETestConfig>(TestEntity.class) {
            @Override
            protected ShardedHibernateFactory getConfig(E2ETestConfig config) {
                return config.getSharding();
            }
        };
        bootstrap.addBundle(bundle);
    }

    @Override
    public void run(E2ETestConfig configuration, Environment environment) {
        dao = bundle.createParentObjectDao(TestEntity.class);
        environment.jersey().register(new E2ETestResource(dao));
    }
}

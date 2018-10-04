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

import com.google.inject.Stage;
import in.cleartax.dropwizard.sharding.providers.ShardKeyProvider;
import in.cleartax.dropwizard.sharding.test.sampleapp.testdata.services.OrderService;
import in.cleartax.dropwizard.sharding.transactions.UnitOfWorkModule;
import io.dropwizard.Application;
import io.dropwizard.jersey.setup.JerseyEnvironment;
import io.dropwizard.setup.Bootstrap;
import io.dropwizard.setup.Environment;
import lombok.Getter;
import ru.vyarus.dropwizard.guice.GuiceBundle;

public class TestApplication extends Application<TestConfig> {
    @Getter
    private GuiceBundle<TestConfig> guiceBundle;
    private TestModule testModule;

    public static void main(String[] args) throws Exception {
        new TestApplication().run(args);
    }

    @Override
    public String getName() {
        return "ShardingDemoApp";
    }

    @Override
    public void initialize(Bootstrap<TestConfig> bootstrap) {
        testModule = new TestModule(bootstrap);
        guiceBundle = GuiceBundle.<TestConfig>builder()
                .modules(testModule, new UnitOfWorkModule())
                .enableAutoConfig(TestModule.PCKGS)
                .build(Stage.PRODUCTION);

        bootstrap.addBundle(guiceBundle);
    }

    @Override
    public void run(TestConfig configuration, Environment environment) throws Exception {
        // Manually initialize as Guice won't detect PCKGs under "test" directory
        OrderService orderService = guiceBundle.getInjector().getInstance(OrderService.class);
        environment.jersey().register(new TestResource(orderService));

        configureFilters(environment.jersey());
    }

    private void configureFilters(JerseyEnvironment environment) {
        ShardKeyProvider shardKeyProvider = guiceBundle.getInjector().getInstance(ShardKeyProvider.class);
        environment.register(new ShardKeyFeature(shardKeyProvider));
    }
}

package io.dropwizard.sharding.test.application;

import com.google.inject.Stage;
import io.dropwizard.Application;
import io.dropwizard.jersey.setup.JerseyEnvironment;
import io.dropwizard.setup.Bootstrap;
import io.dropwizard.setup.Environment;
import io.dropwizard.sharding.providers.ShardKeyProvider;
import io.dropwizard.sharding.test.testdata.services.OrderService;
import io.dropwizard.sharding.transactions.UnitOfWorkModule;
import ru.vyarus.dropwizard.guice.GuiceBundle;

/**
 * Created on 02/10/18
 */
public class TestApplication extends Application<TestConfig> {
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

        // TODO : This should be detected by Guice
        OrderService orderService = guiceBundle.getInjector().getInstance(OrderService.class);
        environment.jersey().register(new TestResource(orderService));

        configureFilters(environment.jersey());
    }

    private void configureFilters(JerseyEnvironment environment) {
        ShardKeyProvider shardKeyProvider = guiceBundle.getInjector().getInstance(ShardKeyProvider.class);
        environment.register(new ShardKeyFeature(shardKeyProvider));
    }
}

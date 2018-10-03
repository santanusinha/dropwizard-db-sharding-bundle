package io.dropwizard.sharding.application;

import io.dropwizard.Application;
import io.dropwizard.setup.Bootstrap;
import io.dropwizard.setup.Environment;
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
                .build();

        bootstrap.addBundle(guiceBundle);
    }

    @Override
    public void run(TestConfig configuration, Environment environment) throws Exception {

    }
}

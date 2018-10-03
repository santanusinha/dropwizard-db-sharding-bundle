package io.dropwizard.sharding;

import io.dropwizard.sharding.application.TestConfig;
import io.dropwizard.testing.junit.DropwizardAppRule;
import org.junit.ClassRule;
import org.junit.Test;

/**
 * Created on 03/10/18
 */
public class OrderIntegrationTest {
    @ClassRule
    public static final DropwizardAppRule<TestConfig> RULE = OrderIntegrationTestSuite.RULE;

    @Test
    public void testCreateOrder() {

    }
}

package com.phonepe.platform.junit.extensions.dbsharding;

import io.appform.dropwizard.sharding.BalancedDBShardingBundle;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.extension.ExtendWith;

import static org.junit.jupiter.api.Assertions.assertNotNull;

@ExtendWith({DBShardingExtension.class})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Disabled
public class BalancedShardingBundleTest {

    private BalancedDBShardingBundle<DBShardingExtension.TestConfig> balancedBundle;

    @BeforeAll
    public void beforeAll(DBBundle bundle) {
        this.balancedBundle = bundle.getBalancedDBShardingBundle();
    }

    @Test
    public void testBalancedShardingBundleIsAvailable() {
        assertNotNull(balancedBundle);
    }

}

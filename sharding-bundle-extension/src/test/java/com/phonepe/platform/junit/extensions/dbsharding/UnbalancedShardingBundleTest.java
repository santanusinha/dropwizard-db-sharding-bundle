package com.phonepe.platform.junit.extensions.dbsharding;

import io.appform.dropwizard.sharding.DBShardingBundle;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.extension.ExtendWith;

import static org.junit.jupiter.api.Assertions.assertNotNull;

@ExtendWith({DBShardingExtension.class})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Disabled
public class UnbalancedShardingBundleTest {

    private DBShardingBundle<DBShardingExtension.TestConfig> unbalancedBundle;

    @BeforeAll
    public void beforeAll(DBBundle bundle) {
        this.unbalancedBundle = bundle.getShardingBundle();
    }

    @Test
    public void testUnbalancedShardingBundleIsAvailable() {
        assertNotNull(unbalancedBundle);
    }

}

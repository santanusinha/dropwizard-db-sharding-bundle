package io.appform.dropwizard.sharding.api;

import io.appform.dropwizard.sharding.dao.DBShardingBundleBase;
import io.appform.dropwizard.sharding.dao.MultiTenantDBShardingBundleBase;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BundlePackageTest {

    @Test
    void bundleBasesLiveInDaoPackage() {
        assertEquals(
                "io.appform.dropwizard.sharding.dao",
                DBShardingBundleBase.class.getPackageName());
        assertEquals(
                "io.appform.dropwizard.sharding.dao",
                MultiTenantDBShardingBundleBase.class.getPackageName());
    }
}

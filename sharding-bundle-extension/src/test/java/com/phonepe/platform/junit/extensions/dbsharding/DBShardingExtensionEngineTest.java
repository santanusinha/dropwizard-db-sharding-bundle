package com.phonepe.platform.junit.extensions.dbsharding;

import org.junit.jupiter.api.Test;
import org.junit.platform.testkit.engine.EngineTestKit;
import org.junit.platform.testkit.engine.Events;

import static org.junit.platform.engine.discovery.DiscoverySelectors.selectClass;
import static org.junit.platform.engine.discovery.DiscoverySelectors.selectMethod;

public class DBShardingExtensionEngineTest {

    @Test
    public void testBothBundlesAreInitialized() {
        System.clearProperty(DBShardingExtension.PROPERTY_BUNDLE_TO_PREPARE);
        EngineTestKit
                .engine("junit-jupiter")
                .configurationParameter("junit.jupiter.conditions.deactivate", "org.junit.*DisabledCondition")
                .selectors(selectClass(UnbalancedShardingBundleTest.class))
                .execute()
                .testEvents()
                .assertStatistics(stats -> stats.started(1).succeeded(1).failed(0));
        EngineTestKit
                .engine("junit-jupiter")
                .configurationParameter("junit.jupiter.conditions.deactivate", "org.junit.*DisabledCondition")
                .selectors(selectClass(BalancedShardingBundleTest.class))
                .execute()
                .testEvents()
                .assertStatistics(stats -> stats.started(1).succeeded(1).failed(0));
    }

    @Test
    public void testOnlyUnbalancedBundleIsInitialized() {
        System.setProperty(DBShardingExtension.PROPERTY_BUNDLE_TO_PREPARE, "UNBALANCED");
        EngineTestKit
                .engine("junit-jupiter")
                .configurationParameter("junit.jupiter.conditions.deactivate", "org.junit.*DisabledCondition")
                .selectors(selectClass(UnbalancedShardingBundleTest.class))
                .execute()
                .testEvents()
                .assertStatistics(stats -> stats.started(1).succeeded(1).failed(0));
        EngineTestKit
                .engine("junit-jupiter")
                .configurationParameter("junit.jupiter.conditions.deactivate", "org.junit.*DisabledCondition")
                .selectors(selectClass(BalancedShardingBundleTest.class))
                .execute()
                .testEvents()
                .assertStatistics(stats -> stats.started(1).succeeded(0).failed(1));
    }

    @Test
    public void testOnlyBalancedBundleIsInitialized() {
        System.setProperty(DBShardingExtension.PROPERTY_BUNDLE_TO_PREPARE, "BALANCED");
        EngineTestKit
                .engine("junit-jupiter")
                .configurationParameter("junit.jupiter.conditions.deactivate", "org.junit.*DisabledCondition")
                .selectors(selectClass(UnbalancedShardingBundleTest.class))
                .execute()
                .testEvents()
                .assertStatistics(stats -> stats.started(1).succeeded(0).failed(1));
        EngineTestKit
                .engine("junit-jupiter")
                .configurationParameter("junit.jupiter.conditions.deactivate", "org.junit.*DisabledCondition")
                .selectors(selectClass(BalancedShardingBundleTest.class))
                .execute()
                .testEvents()
                .assertStatistics(stats -> stats.started(1).succeeded(1).failed(0));
    }


}

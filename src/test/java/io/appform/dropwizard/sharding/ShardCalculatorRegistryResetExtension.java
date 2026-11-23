package io.appform.dropwizard.sharding;

import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

/**
 * Auto-registered for every test so each test starts with an empty registry.
 */
public class ShardCalculatorRegistryResetExtension implements BeforeEachCallback, AfterEachCallback {

    @Override
    public void beforeEach(ExtensionContext context) {
        TestAccess.reset();
    }

    @Override
    public void afterEach(ExtensionContext context) {
        TestAccess.reset();
    }
}

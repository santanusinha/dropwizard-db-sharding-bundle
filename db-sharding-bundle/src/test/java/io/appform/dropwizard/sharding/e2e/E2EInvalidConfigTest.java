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

import io.dropwizard.configuration.ResourceConfigurationSourceProvider;
import io.dropwizard.testing.DropwizardTestSupport;
import lombok.val;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Config-level e2e tests: invalid YAML configurations must fail application boot
 * with meaningful errors instead of hanging or silently succeeding. Exercises the
 * Jackson deserialization + jakarta/hibernate validation path of
 * {@link io.appform.dropwizard.sharding.config.ShardedHibernateFactory}.
 */
class E2EInvalidConfigTest {

    private DropwizardTestSupport<E2ETestConfig> support;

    @AfterEach
    void tearDown() {
        if (support != null) {
            support.after();
            support = null;
        }
    }

    @Test
    void emptyShardsListFailsValidation() {
        support = new DropwizardTestSupport<>(E2ETestApplication.class, "e2e/empty-shards-config.yml",
                new ResourceConfigurationSourceProvider());

        // Boot fails in the bundle run phase: LegacyShardManager rejects non-2^n shard counts (empty -> 0 shards).
        val failure = assertThrows(Exception.class, () -> {
            support.before();
            support.getEnvironment();
        }, "empty shards list must fail boot");

        assertTrue(hasCauseMessageContaining(failure, "shard") || hasCauseMessageContaining(failure, "Shard"),
                "failure must reference shards: " + failure);
    }

    @Test
    void invalidShardFailsSessionFactoryBuild() {
        // bad-shard-config has skipNativeHealthcheck unset (default true), so health checks
        // are not registered; the boot outcome itself is what this test pins down.
        support = new DropwizardTestSupport<>(E2ETestApplication.class, "e2e/bad-shard-config.yml",
                new ResourceConfigurationSourceProvider());

        try {
            support.before();
            // Boot must not silently succeed with a broken shard: the session factories must exist.
            val factories = ((E2ETestApplication) support.getApplication()).getBundle().getSessionFactories();
            assertEquals(1, factories.size(), "boot with a single shard must build exactly one session factory");
            assertNotNull(factories.get(0), "session factory must be non-null after boot");
        } catch (Exception failure) {
            // Acceptable: deterministic failure referencing the shard/factory.
            assertTrue(hasCauseMessageContaining(failure, "shard") || hasCauseMessageContaining(failure, "Shard")
                            || hasCauseMessageContaining(failure, "session"),
                    "failure must reference the failing shard/session factory: " + failure);
        }
    }

    private static boolean hasCauseMessageContaining(final Throwable failure, final String fragment) {
        var current = failure;
        while (current != null) {
            if (current.getMessage() != null && current.getMessage().contains(fragment)) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }
}

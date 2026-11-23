/*
 * Copyright 2019 Santanu Sinha <santanu.sinha@gmail.com>
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

package io.appform.dropwizard.sharding;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * JVM-wide, write-once registry of per-tenant shard calculators.
 * Only the sharding bundle can register; anyone can read.
 */
public final class ShardCalculatorRegistry {

    private static final ConcurrentMap<String, ShardCalculator> CALCULATORS = new ConcurrentHashMap<>();

    private ShardCalculatorRegistry() {
        throw new AssertionError("No instances");
    }

    public static ShardCalculator get(String tenantId) {
        final var calculator = tenantId == null ? null : CALCULATORS.get(tenantId);
        if (calculator == null) {
            throw new IllegalArgumentException("Unknown tenant: " + tenantId);
        }
        return calculator;
    }

    public static boolean contains(String tenantId) {
        return tenantId != null && CALCULATORS.containsKey(tenantId);
    }

    public static Map<String, ShardCalculator> all() {
        return Map.copyOf(CALCULATORS);
    }

    static void register(ShardCalculator calculator) {
        Objects.requireNonNull(calculator, "calculator");
        if (CALCULATORS.putIfAbsent(calculator.tenantId(), calculator) != null) {
            throw new IllegalStateException(
                    "Shard calculator already registered for tenant: " + calculator.tenantId());
        }
    }

    static void clear() {
        CALCULATORS.clear();
    }
}

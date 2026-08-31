package io.appform.dropwizard.sharding.sanity.tests;

import io.appform.dropwizard.sharding.sanity.base.SanityTestBase;
import io.appform.dropwizard.sharding.sanity.entities.SanityOrder;
import io.appform.dropwizard.sharding.sanity.entities.SanityOrderItem;
import lombok.val;
import org.hibernate.criterion.DetachedCriteria;
import org.hibernate.criterion.Restrictions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for the {@code RunWithCriteria} opContext.
 *
 * <p>Public methods that use this opContext:
 * <ul>
 *   <li>{@code LookupDao.run(DetachedCriteria)} — raw query across all shards, returns per-shard results</li>
 *   <li>{@code LookupDao.run(DetachedCriteria, Function translator)} — same with result transformation</li>
 *   <li>{@code RelationalDao.run(DetachedCriteria)} — raw query across all shards</li>
 *   <li>{@code RelationalDao.run(DetachedCriteria, Function translator)} — same with transformation</li>
 * </ul>
 */
class RunWithCriteriaOpContextTest extends SanityTestBase {

    @BeforeEach
    void cleanTables() throws Exception {
        truncateAllTables();
    }

    // =========================================================================
    // LookupDao.run(DetachedCriteria)
    // =========================================================================

    @Test
    void lookupDao_run_returnsPerShardResults() throws Exception {
        orderLookupDao.save(SanityOrder.builder().orderId(UUID.randomUUID().toString()).customerId("c1").amount(100).build());
        orderLookupDao.save(SanityOrder.builder().orderId(UUID.randomUUID().toString()).customerId("c2").amount(200).build());

        val criteria = DetachedCriteria.forClass(SanityOrder.class);
        val perShardResults = orderLookupDao.run(criteria);

        // Map<Integer, List<T>> — keys are shard indices
        int totalEntities = perShardResults.values().stream()
                .mapToInt(List::size).sum();
        assertEquals(2, totalEntities, "Total entities across all shards must be 2");
    }

    @Test
    void lookupDao_run_noMatches_emptyShardsInMap() throws Exception {
        val criteria = DetachedCriteria.forClass(SanityOrder.class)
                .add(Restrictions.eq("customerId", "nonexistent"));
        val perShardResults = orderLookupDao.run(criteria);

        int totalEntities = perShardResults.values().stream()
                .mapToInt(List::size).sum();
        assertEquals(0, totalEntities);
    }

    // =========================================================================
    // LookupDao.run(DetachedCriteria, Function translator)
    // =========================================================================

    @Test
    void lookupDao_run_withTranslator() throws Exception {
        orderLookupDao.save(SanityOrder.builder().orderId(UUID.randomUUID().toString()).customerId("c1").amount(100).build());
        orderLookupDao.save(SanityOrder.builder().orderId(UUID.randomUUID().toString()).customerId("c2").amount(200).build());
        orderLookupDao.save(SanityOrder.builder().orderId(UUID.randomUUID().toString()).customerId("c3").amount(300).build());

        val criteria = DetachedCriteria.forClass(SanityOrder.class);
        int totalCount = orderLookupDao.run(criteria,
                perShard -> perShard.values().stream().mapToInt(List::size).sum());

        assertEquals(3, totalCount);
    }

    @Test
    void lookupDao_run_withTranslator_sumAmounts() throws Exception {
        orderLookupDao.save(SanityOrder.builder().orderId(UUID.randomUUID().toString()).customerId("c1").amount(100).build());
        orderLookupDao.save(SanityOrder.builder().orderId(UUID.randomUUID().toString()).customerId("c2").amount(200).build());

        val criteria = DetachedCriteria.forClass(SanityOrder.class);
        int totalAmount = orderLookupDao.run(criteria,
                perShard -> perShard.values().stream()
                        .flatMap(List::stream)
                        .mapToInt(SanityOrder::getAmount)
                        .sum());

        assertEquals(300, totalAmount);
    }

    // =========================================================================
    // RelationalDao.run(DetachedCriteria)
    // =========================================================================

    @Test
    void relationalDao_run_returnsPerShardResults() throws Exception {
        val parentKey1 = UUID.randomUUID().toString();
        val parentKey2 = UUID.randomUUID().toString();

        orderItemDao.save(parentKey1, SanityOrderItem.builder()
                .orderId(parentKey1).itemName("item-1").quantity(1).price(100).build());
        orderItemDao.save(parentKey2, SanityOrderItem.builder()
                .orderId(parentKey2).itemName("item-2").quantity(2).price(200).build());

        val criteria = DetachedCriteria.forClass(SanityOrderItem.class);
        val perShardResults = orderItemDao.run(criteria);

        int totalEntities = perShardResults.values().stream()
                .mapToInt(List::size).sum();
        assertEquals(2, totalEntities);
    }

    // =========================================================================
    // RelationalDao.run(DetachedCriteria, Function translator)
    // =========================================================================

    @Test
    void relationalDao_run_withTranslator() throws Exception {
        val parentKey = UUID.randomUUID().toString();

        for (int i = 1; i <= 3; i++) {
            orderItemDao.save(parentKey, SanityOrderItem.builder()
                    .orderId(parentKey).itemName("item-" + i).quantity(i).price(i * 10).build());
        }

        val criteria = DetachedCriteria.forClass(SanityOrderItem.class);
        int totalCount = orderItemDao.run(criteria,
                perShard -> perShard.values().stream().mapToInt(List::size).sum());

        assertEquals(3, totalCount);
    }
}

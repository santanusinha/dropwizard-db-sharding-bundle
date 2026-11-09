package io.appform.dropwizard.sharding.sanity.tests;

import io.appform.dropwizard.sharding.sanity.base.SanityTestBase;
import io.appform.dropwizard.sharding.sanity.entities.SanityOrder;
import io.appform.dropwizard.sharding.sanity.entities.SanityOrderItem;
import lombok.val;
import org.hibernate.criterion.DetachedCriteria;
import org.hibernate.criterion.Restrictions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for the {@code Count} and {@code CountByQuerySpec} opContexts.
 *
 * <p>Methods using Count opContext:
 * <ul>
 *   <li>{@code LookupDao.count(DetachedCriteria)} — scatter-gather, one txn per shard</li>
 *   <li>{@code RelationalDao.count(parentKey, DetachedCriteria)} — single shard</li>
 *   <li>{@code RelationalDao.countScatterGather(DetachedCriteria)} — one txn per shard</li>
 * </ul>
 *
 * <p>Methods using CountByQuerySpec opContext:
 * <ul>
 *   <li>{@code RelationalDao.count(parentKey, QuerySpec)} — single shard</li>
 * </ul>
 *
 * <p>Scatter-gather methods run one transaction per shard (NUM_SHARDS transactions),
 * so checkpoint captures NUM_SHARDS * (BEGIN + GET_CONN + PREPARE + COMMIT + RELEASE)
 * events. Single-shard methods run one transaction.
 */
class CountOpContextTest extends SanityTestBase {

    @BeforeEach
    void cleanTables() throws Exception {
        truncateAllTables();
    }

    // =========================================================================
    // 6.1 Count matches expected
    // =========================================================================

    @Test
    void lookupDao_count_matchesExpected() throws Exception {
        for (int i = 0; i < 3; i++) {
            orderLookupDao.save(SanityOrder.builder()
                    .orderId(UUID.randomUUID().toString()).customerId("c" + i).amount(100).build());
        }

        // scatter-gather: one transaction per shard
        val result = checkpoint(() -> {
            val criteria = DetachedCriteria.forClass(SanityOrder.class);
            return orderLookupDao.count(criteria);
        });

        // NUM_SHARDS transactions, each with 1 prepare
        // scatter-gather: one transaction per shard, 1 prepare per shard
        assertScatterGatherEvents(result, NUM_SHARDS, 1, true);

        long total = result.getValue().stream().mapToLong(Long::longValue).sum();
        assertEquals(3, total);
    }

    @Test
    void relationalDao_count_matchesExpected() throws Exception {
        val parentKey = UUID.randomUUID().toString();

        for (int i = 1; i <= 4; i++) {
            orderItemDao.save(parentKey, SanityOrderItem.builder()
                    .orderId(parentKey).itemName("item-" + i).quantity(i).price(i * 10).build());
        }

        // single shard: 1 transaction, 1 prepare
        val result = checkpoint(() -> {
            try {
                val criteria = DetachedCriteria.forClass(SanityOrderItem.class)
                        .add(Restrictions.eq("orderId", parentKey));
                return orderItemDao.count(parentKey, criteria);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });

        assertTransactionEvents(result, 1, true);
        assertEquals(4, result.getValue());
    }

    @Test
    void relationalDao_count_withFilter() throws Exception {
        val parentKey = UUID.randomUUID().toString();

        for (int i = 1; i <= 5; i++) {
            orderItemDao.save(parentKey, SanityOrderItem.builder()
                    .orderId(parentKey).itemName("item-" + i).quantity(i).price(i * 100).build());
        }

        val result = checkpoint(() -> {
            try {
                val criteria = DetachedCriteria.forClass(SanityOrderItem.class)
                        .add(Restrictions.eq("orderId", parentKey))
                        .add(Restrictions.gt("price", 300));
                return orderItemDao.count(parentKey, criteria);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });

        assertTransactionEvents(result, 1, true);
        assertEquals(2, result.getValue(), "Only items with price > 300 should be counted");
    }

    @Test
    void relationalDao_countScatterGather() throws Exception {
        val pk1 = UUID.randomUUID().toString();
        val pk2 = UUID.randomUUID().toString();

        orderItemDao.save(pk1, SanityOrderItem.builder()
                .orderId(pk1).itemName("sg-1").quantity(1).price(10).build());
        orderItemDao.save(pk2, SanityOrderItem.builder()
                .orderId(pk2).itemName("sg-2").quantity(2).price(20).build());

        val result = checkpoint(() -> {
            val criteria = DetachedCriteria.forClass(SanityOrderItem.class);
            return orderItemDao.countScatterGather(criteria);
        });

        // scatter-gather: NUM_SHARDS transactions
        assertScatterGatherEvents(result, NUM_SHARDS, 1, true);

        long total = result.getValue().stream().mapToLong(Long::longValue).sum();
        assertEquals(2, total);
    }

    // =========================================================================
    // 6.2 Count returns 0 for no matches
    // =========================================================================

    @Test
    void lookupDao_count_noMatches_returnsZero() throws Exception {
        val result = checkpoint(() -> {
            val criteria = DetachedCriteria.forClass(SanityOrder.class)
                    .add(Restrictions.eq("customerId", "nonexistent"));
            return orderLookupDao.count(criteria);
        });

        assertScatterGatherEvents(result, NUM_SHARDS, 1, true);
        long total = result.getValue().stream().mapToLong(Long::longValue).sum();
        assertEquals(0, total);
    }

    @Test
    void relationalDao_count_noMatches_returnsZero() throws Exception {
        val parentKey = UUID.randomUUID().toString();

        val result = checkpoint(() -> {
            try {
                val criteria = DetachedCriteria.forClass(SanityOrderItem.class)
                        .add(Restrictions.eq("orderId", parentKey));
                return orderItemDao.count(parentKey, criteria);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });

        assertTransactionEvents(result, 1, true);
        assertEquals(0, result.getValue());
    }

    // =========================================================================
    // 6.3 Count reflects committed deletes/updates
    // =========================================================================

    @Test
    void lookupDao_count_reflectsDeletes() throws Exception {
        val id1 = UUID.randomUUID().toString();
        val id2 = UUID.randomUUID().toString();
        val id3 = UUID.randomUUID().toString();
        val id4 = UUID.randomUUID().toString();
        val id5 = UUID.randomUUID().toString();

        orderLookupDao.save(SanityOrder.builder().orderId(id1).customerId("c").amount(100).build());
        orderLookupDao.save(SanityOrder.builder().orderId(id2).customerId("c").amount(100).build());
        orderLookupDao.save(SanityOrder.builder().orderId(id3).customerId("c").amount(100).build());
        orderLookupDao.save(SanityOrder.builder().orderId(id4).customerId("c").amount(100).build());
        orderLookupDao.save(SanityOrder.builder().orderId(id5).customerId("c").amount(100).build());

        val criteriaBefore = DetachedCriteria.forClass(SanityOrder.class)
                .add(Restrictions.eq("customerId", "c"));
        val result = checkpoint(() -> orderLookupDao.count(criteriaBefore).stream().mapToLong(Long::longValue).sum());

        assertScatterGatherEvents(result, NUM_SHARDS, 1, true);
        assertEquals(5, result.getValue());

        orderLookupDao.delete(id1);
        orderLookupDao.delete(id2);

        val resultAfterDelete = checkpoint(() -> {
            val criteria = DetachedCriteria.forClass(SanityOrder.class)
                    .add(Restrictions.eq("customerId", "c"));
            return orderLookupDao.count(criteria);
        });

        assertScatterGatherEvents(resultAfterDelete, NUM_SHARDS, 1, true);
        long after = resultAfterDelete.getValue().stream().mapToLong(Long::longValue).sum();
        assertEquals(3, after, "Count must reflect committed deletes");
    }

    @Test
    void relationalDao_count_reflectsUpdates() throws Exception {
        val parentKey = UUID.randomUUID().toString();

        for (int i = 1; i <= 5; i++) {
            orderItemDao.save(parentKey, SanityOrderItem.builder()
                    .orderId(parentKey).itemName("item-" + i).quantity(i).price(100).build());
        }

        val allItems = orderItemDao.select(parentKey,
                DetachedCriteria.forClass(SanityOrderItem.class).add(Restrictions.eq("orderId", parentKey)),
                0, Integer.MAX_VALUE);
        orderItemDao.update(parentKey, allItems.get(0).getId(), item -> { item.setPrice(999); return item; });
        orderItemDao.update(parentKey, allItems.get(1).getId(), item -> { item.setPrice(999); return item; });

        val result = checkpoint(() -> {
            try {
                val criteria = DetachedCriteria.forClass(SanityOrderItem.class)
                        .add(Restrictions.eq("orderId", parentKey))
                        .add(Restrictions.eq("price", 100));
                return orderItemDao.count(parentKey, criteria);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });

        assertTransactionEvents(result, 1, true);
        assertEquals(3, result.getValue(), "Count must reflect committed updates");
    }

    // =========================================================================
    // 7. CountByQuerySpec — RelationalDao.count(parentKey, QuerySpec<T, Long>)
    // =========================================================================

    // -------------------------------------------------------------------------
    // 7.1 CountByQuerySpec matches expected
    // -------------------------------------------------------------------------

    @Test
    void relationalDao_countByQuerySpec_matchesExpected() throws Exception {
        val parentKey = UUID.randomUUID().toString();

        for (int i = 1; i <= 4; i++) {
            orderItemDao.save(parentKey, SanityOrderItem.builder()
                    .orderId(parentKey).itemName("qs-item-" + i).quantity(i).price(i * 100).build());
        }

        val result = checkpoint(() -> orderItemDao.count(parentKey,
                (root, query, builder) ->
                        query.where(builder.equal(root.get("orderId"), parentKey))));

        assertTransactionEvents(result, 1, true);
        assertEquals(4, result.getValue());
    }

    // -------------------------------------------------------------------------
    // 7.2 CountByQuerySpec returns 0 for no matches
    // -------------------------------------------------------------------------

    @Test
    void relationalDao_countByQuerySpec_noMatches_returnsZero() throws Exception {
        val parentKey = UUID.randomUUID().toString();

        for (int i = 1; i <= 4; i++) {
            orderItemDao.save(parentKey+"x", SanityOrderItem.builder()
                    .orderId(parentKey+"x").itemName("qs-item-" + i).quantity(i).price(i * 100).build());
        }

        val result = checkpoint(() -> orderItemDao.count(parentKey,
                (root, query, builder) ->
                        query.where(builder.equal(root.get("orderId"), parentKey))));

        assertTransactionEvents(result, 1, true);
        assertEquals(0, result.getValue());
    }

    // -------------------------------------------------------------------------
    // 7.3 CountByQuerySpec with filter
    // -------------------------------------------------------------------------

    @Test
    void relationalDao_countByQuerySpec_withFilter() throws Exception {
        val parentKey = UUID.randomUUID().toString();

        for (int i = 1; i <= 5; i++) {
            orderItemDao.save(parentKey, SanityOrderItem.builder()
                    .orderId(parentKey).itemName("qs-item-" + i).quantity(i).price(i * 100).build());
        }

        val result = checkpoint(() -> orderItemDao.count(parentKey,
                (root, query, builder) ->
                        query.where(builder.and(
                                builder.equal(root.get("orderId"), parentKey),
                                builder.gt(root.get("price"), 300)))));

        assertTransactionEvents(result, 1, true);
        assertEquals(2, result.getValue(), "Only items with price > 300 should be counted");
    }

    // -------------------------------------------------------------------------
    // 7.4 CountByQuerySpec reflects committed updates
    // -------------------------------------------------------------------------

    @Test
    void relationalDao_countByQuerySpec_reflectsUpdates() throws Exception {
        val parentKey = UUID.randomUUID().toString();

        for (int i = 1; i <= 3; i++) {
            orderItemDao.save(parentKey, SanityOrderItem.builder()
                    .orderId(parentKey).itemName("qs-item-" + i).quantity(i).price(100).build());
        }

        val allItems = orderItemDao.select(parentKey,
                DetachedCriteria.forClass(SanityOrderItem.class).add(Restrictions.eq("orderId", parentKey)),
                0, Integer.MAX_VALUE);
        orderItemDao.update(parentKey, allItems.get(0).getId(), item -> { item.setPrice(999); return item; });

        val result = checkpoint(() -> orderItemDao.count(parentKey,
                (root, query, builder) ->
                        query.where(builder.and(
                                builder.equal(root.get("orderId"), parentKey),
                                builder.equal(root.get("price"), 100)))));

        assertTransactionEvents(result, 1, true);
        assertEquals(2, result.getValue(), "Count must reflect committed update");
    }

    // =========================================================================
    // Existence checks
    // =========================================================================

    @Test
    void lookupDao_exists_returnsTrueForExisting() throws Exception {
        val orderId = UUID.randomUUID().toString();
        orderLookupDao.save(SanityOrder.builder().orderId(orderId).customerId("c1").amount(100).build());

        val result = checkpoint(() -> {
            try {
                return orderLookupDao.exists(orderId);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });

        assertTransactionEvents(result, 1, true);
        assertTrue(result.getValue());
    }

    @Test
    void lookupDao_exists_returnsFalseForNonExistent() throws Exception {
        val orderId = UUID.randomUUID().toString();
        orderLookupDao.save(SanityOrder.builder().orderId(orderId).customerId("c1").amount(100).build());

        val result = checkpoint(() -> {
            try {
                return orderLookupDao.exists(orderId+"non-existent");
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });

        assertTransactionEvents(result, 1, true);
        assertFalse(result.getValue());
    }

    @Test
    void lookupDao_exists_returnsFalseAfterDelete() throws Exception {
        val orderId = UUID.randomUUID().toString();
        orderLookupDao.save(SanityOrder.builder().orderId(orderId).customerId("c1").amount(100).build());
        assertTrue(orderLookupDao.exists(orderId));

        orderLookupDao.delete(orderId);

        val result = checkpoint(() -> {
            try {
                return orderLookupDao.exists(orderId);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });

        assertTransactionEvents(result, 1, true);
        assertFalse(result.getValue(), "exists must return false after delete");
    }

    @Test
    void relationalDao_exists_returnsTrueForExisting() throws Exception {
        val parentKey = UUID.randomUUID().toString();
        val saved = orderItemDao.save(parentKey, SanityOrderItem.builder()
                .orderId(parentKey).itemName("exists-item").quantity(1).price(10).build());

        val result = checkpoint(() -> {
            try {
                return orderItemDao.exists(parentKey, saved.get().getId());
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });

        assertTransactionEvents(result, 1, true);
        assertTrue(result.getValue());
    }

    @Test
    void relationalDao_exists_returnsFalseForNonExistent() throws Exception {
        val parentKey = UUID.randomUUID().toString();

        val result = checkpoint(() -> {
            try {
                return orderItemDao.exists(parentKey, 999999L);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });

        assertTransactionEvents(result, 1, true);
        assertFalse(result.getValue());
    }
}

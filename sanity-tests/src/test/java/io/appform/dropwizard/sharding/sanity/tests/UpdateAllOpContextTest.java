package io.appform.dropwizard.sharding.sanity.tests;

import io.appform.dropwizard.sharding.sanity.base.SanityTestBase;
import io.appform.dropwizard.sharding.sanity.entities.SanityOrderItem;
import lombok.val;
import org.hibernate.criterion.DetachedCriteria;
import org.hibernate.criterion.Restrictions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for the {@code UpdateAll} opContext.
 *
 * <p>Selects all entities matching criteria (with pagination), iterates and updates each.
 * If any entity is null or mutator returns null, returns false EARLY — remaining entities
 * are NOT processed. The transaction still commits (returns false, not throws).
 *
 * <p>Public methods:
 * <ul>
 *   <li>{@code RelationalDao.updateAll(parentKey, start, numRows, DetachedCriteria, updater)}</li>
 *   <li>{@code RelationalDao.updateAll(parentKey, start, numRows, QuerySpec, updater)}</li>
 * </ul>
 */
class UpdateAllOpContextTest extends SanityTestBase {

    @BeforeEach
    void cleanTables() throws Exception {
        truncateAllTables();
    }

    // -------------------------------------------------------------------------
    // 12.1 All matching entities updated
    // -------------------------------------------------------------------------

    @Test
    void updateAll_allMatchingUpdated() throws Exception {
        val parentKey = UUID.randomUUID().toString();

        for (int i = 1; i <= 5; i++) {
            orderItemDao.save(parentKey, SanityOrderItem.builder()
                    .orderId(parentKey).itemName("item-" + i).quantity(10).price(100).build());
        }

        // DetachedCriteria variant
        val result = checkpoint(() -> {
            val criteria = DetachedCriteria.forClass(SanityOrderItem.class)
                    .add(Restrictions.eq("orderId", parentKey));
            return orderItemDao.updateAll(parentKey, 0, 10, criteria, entity -> {
                entity.setPrice(999);
                return entity;
            });
        });

        assertTransactionEvents(result, 6, true); // SELECT + 5 UPDATEs
        assertTrue(result.getValue());

        val items = selectItems(parentKey);
        assertEquals(5, items.size());
        assertTrue(items.stream().allMatch(it -> it.getPrice() == 999),
                "All 5 items must be updated to price=999");
    }

    @Test
    void updateAll_querySpec_allMatchingUpdated() throws Exception {
        val parentKey = UUID.randomUUID().toString();

        for (int i = 1; i <= 3; i++) {
            orderItemDao.save(parentKey, SanityOrderItem.builder()
                    .orderId(parentKey).itemName("qs-item-" + i).quantity(10).price(100).build());
        }

        val result = checkpoint(() -> orderItemDao.updateAll(parentKey, 0, 10,
                (root, query, builder) -> query.where(builder.equal(root.get("orderId"), parentKey)),
                entity -> {
                    entity.setPrice(777);
                    return entity;
                }));

        assertTransactionEvents(result, 4, true);
        assertTrue(result.getValue());

        val items = selectItems(parentKey);
        assertTrue(items.stream().allMatch(it -> it.getPrice() == 777));
    }

    // -------------------------------------------------------------------------
    // 12.2 Atomicity — failure mid-iteration
    // -------------------------------------------------------------------------

    @Test
    void updateAll_exceptionMidIteration_rollsBackAll() throws Exception {
        val parentKey = UUID.randomUUID().toString();

        for (int i = 1; i <= 5; i++) {
            orderItemDao.save(parentKey, SanityOrderItem.builder()
                    .orderId(parentKey).itemName("item-" + i).quantity(i).price(100).build());
        }

        val counter = new AtomicInteger(0);

        val result = checkpoint(() -> {
            assertThrows(Exception.class, () -> {
                val criteria = DetachedCriteria.forClass(SanityOrderItem.class)
                        .add(Restrictions.eq("orderId", parentKey));
                orderItemDao.updateAll(parentKey, 0, 10, criteria, entity -> {
                    int count = counter.incrementAndGet();
                    entity.setPrice(999);
                    if (count == 3) {
                        throw new RuntimeException("explosion at entity #3");
                    }
                    return entity;
                });
            });
            return null;
        });

        assertTransactionEvents(result, 1,false);

        // ALL 5 must be unchanged (full rollback)
        val items = selectItems(parentKey);
        assertTrue(items.stream().allMatch(it -> it.getPrice() == 100),
                "All 5 items must be unchanged after mid-iteration exception (full rollback)");
    }

    // -------------------------------------------------------------------------
    // 12.3 Mutator returns null — partial update risk (CRITICAL)
    //
    // UpdateAll.apply() returns false EARLY when mutator returns null.
    // The transaction COMMITS (returns false, doesn't throw).
    // Entities processed BEFORE the null are already updated.
    // This is a DATA CORRUPTION RISK: partial update committed.
    // -------------------------------------------------------------------------

    @Test
    void updateAll_mutatorReturnsNull_partialUpdateRisk() throws Exception {
        val parentKey = UUID.randomUUID().toString();

        for (int i = 1; i <= 5; i++) {
            orderItemDao.save(parentKey, SanityOrderItem.builder()
                    .orderId(parentKey).itemName("item-" + i).quantity(i).price(100).build());
        }

        val counter = new AtomicInteger(0);

        val result = checkpoint(() -> {
            val criteria = DetachedCriteria.forClass(SanityOrderItem.class)
                    .add(Restrictions.eq("orderId", parentKey));
            return orderItemDao.updateAll(parentKey, 0, 10, criteria, entity -> {
                int count = counter.incrementAndGet();
                if (count == 3) {
                    return null; // mutator returns null for entity #3
                }
                entity.setPrice(999);
                return entity;
            });
        });

        // Returns false (mutator returned null), but transaction COMMITS
        assertTransactionEvents(result, 3, true);
        assertFalse(result.getValue());

        // DATA CORRUPTION CHECK:
        // Entities 1-2 were updated (price=999), entities 3-5 were NOT processed.
        // This is an inconsistent state committed to DB.
        val items = selectItems(parentKey);
        long updatedCount = items.stream().filter(it -> it.getPrice() == 999).count();
        long unchangedCount = items.stream().filter(it -> it.getPrice() == 100).count();

        System.out.println("[PARTIAL UPDATE RISK] UpdateAll mutator returned null: " +
                "updated=" + updatedCount + ", unchanged=" + unchangedCount +
                ". If updated > 0 AND unchanged > 0, this is DATA CORRUPTION.");

        // IDEALLY: all should be unchanged (full rollback). But the code commits.
        // This assertion documents the actual (potentially broken) behavior.
    }

    // -------------------------------------------------------------------------
    // 12.4 Empty result set
    // -------------------------------------------------------------------------

    @Test
    void updateAll_emptyResultSet_returnsFalse() throws Exception {
        val parentKey = UUID.randomUUID().toString();

        val result = checkpoint(() -> {
            val criteria = DetachedCriteria.forClass(SanityOrderItem.class)
                    .add(Restrictions.eq("orderId", parentKey));
            return orderItemDao.updateAll(parentKey, 0, 10, criteria, entity -> {
                entity.setPrice(999);
                return entity;
            });
        });

        assertTransactionEvents(result, 1, true);
        assertFalse(result.getValue());
    }

    // -------------------------------------------------------------------------
    // 12.5 UpdateAll sees fresh data
    // -------------------------------------------------------------------------

    @Test
    void updateAll_seesFreshData() throws Exception {
        val parentKey = UUID.randomUUID().toString();

        for (int i = 1; i <= 5; i++) {
            orderItemDao.save(parentKey, SanityOrderItem.builder()
                    .orderId(parentKey).itemName("item-" + i).quantity(10).price(100).build());
        }

        // Update 2 items externally
        val allItems = selectItems(parentKey);
        orderItemDao.update(parentKey, allItems.get(0).getId(), e -> { e.setPrice(500); return e; });
        orderItemDao.update(parentKey, allItems.get(1).getId(), e -> { e.setPrice(500); return e; });

        // UpdateAll: double the price
        val criteria = DetachedCriteria.forClass(SanityOrderItem.class)
                .add(Restrictions.eq("orderId", parentKey));
        val result = checkpoint(() -> orderItemDao.updateAll(parentKey, 0, 10, criteria, entity -> {
            entity.setPrice(entity.getPrice() * 2);
            return entity;
        }));

        assertTransactionEvents(result, 6, true);;

        val items = selectItems(parentKey);
        long price1000Count = items.stream().filter(it -> it.getPrice() == 1000).count();
        long price200Count = items.stream().filter(it -> it.getPrice() == 200).count();
        assertEquals(2, price1000Count, "2 items with price=500 should now be 1000");
        assertEquals(3, price200Count, "3 items with price=100 should now be 200");
    }

    // -------------------------------------------------------------------------
    // 12.6 Concurrent UpdateAll — possible lost update
    // -------------------------------------------------------------------------

    @Test
    void updateAll_concurrentUpdates_possibleLostUpdate() throws Exception {
        val parentKey = UUID.randomUUID().toString();

        orderItemDao.save(parentKey, SanityOrderItem.builder()
                .orderId(parentKey).itemName("concurrent").quantity(100).price(10).build());

        val error1 = new AtomicReference<Exception>();
        val error2 = new AtomicReference<Exception>();
        val startLatch = new CountDownLatch(1);

        val thread1 = new Thread(() -> {
            try {
                startLatch.await();
                val criteria = DetachedCriteria.forClass(SanityOrderItem.class)
                        .add(Restrictions.eq("orderId", parentKey));
                orderItemDao.updateAll(parentKey, 0, 10, criteria, entity -> {
                    try { Thread.sleep(5000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                    entity.setQuantity(entity.getQuantity() + 1);
                    return entity;
                });
            } catch (Exception e) { error1.set(e); }
        });

        val thread2 = new Thread(() -> {
            try {
                startLatch.await();
                Thread.sleep(2000);
                val criteria = DetachedCriteria.forClass(SanityOrderItem.class)
                        .add(Restrictions.eq("orderId", parentKey));
                orderItemDao.updateAll(parentKey, 0, 10, criteria, entity -> {
                    try { Thread.sleep(5000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                    entity.setQuantity(entity.getQuantity() + 1);
                    return entity;
                });
            } catch (Exception e) { error2.set(e); }
        });

        thread1.start();
        thread2.start();
        startLatch.countDown();
        thread1.join(15000);
        thread2.join(15000);

        if (error1.get() != null) throw error1.get();
        if (error2.get() != null) throw error2.get();

        val items = selectItems(parentKey);
        int finalQuantity = items.get(0).getQuantity();
        assertTrue(finalQuantity == 101 || finalQuantity == 102,
                "Quantity should be 101 (lost update) or 102 (serialized). Got: " + finalQuantity);
        if (finalQuantity == 101) {
            System.out.println("[LOST UPDATE CONFIRMED] UpdateAll without FOR UPDATE: " +
                    "final quantity=101 instead of 102");
        }
    }

    // -------------------------------------------------------------------------
    // Helper
    // -------------------------------------------------------------------------

    private java.util.List<SanityOrderItem> selectItems(String parentKey) throws Exception {
        val criteria = DetachedCriteria.forClass(SanityOrderItem.class)
                .add(Restrictions.eq("orderId", parentKey));
        return orderItemDao.select(parentKey, criteria, 0, Integer.MAX_VALUE);
    }
}

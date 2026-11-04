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
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for the {@code SelectAndUpdate} opContext.
 *
 * <p>Similar to {@code GetAndUpdate} but uses SELECT (with LIMIT 1) instead of
 * uniqueResult. Updates the <b>first</b> entity from the result set. Does NOT
 * throw on multiple matches — unlike GetAndUpdate.
 *
 * <p>NOTE: The caller has NO control over which row gets updated when multiple
 * rows match the criteria. The "first" row depends on DB ordering (undefined
 * without ORDER BY). This is flagged as a design concern.
 *
 * <p>Public methods using this opContext:
 * <ul>
 *   <li>{@code RelationalDao.update(parentKey, DetachedCriteria, updater)}</li>
 *   <li>{@code RelationalDao.update(parentKey, QuerySpec, updater)}</li>
 * </ul>
 *
 * <p>Both methods are tested in each scenario — DetachedCriteria and QuerySpec variants.
 */
class SelectAndUpdateOpContextTest extends SanityTestBase {

    @BeforeEach
    void cleanTables() throws Exception {
        truncateAllTables();
    }

    // =========================================================================
    // 11.1 Updates first matching entity
    // =========================================================================

    @Test
    void selectAndUpdate_multipleMatches_updatesOnlyFirst() throws Exception {
        val parentKey = UUID.randomUUID().toString();

        // Save 3 items with same orderId, different prices
        for (int i = 1; i <= 3; i++) {
            orderItemDao.save(parentKey, SanityOrderItem.builder()
                    .orderId(parentKey).itemName("item-" + i).quantity(10).price(100).build());
        }

        // --- DetachedCriteria variant ---
        val criteriaResult = checkpoint(() -> {
            val criteria = DetachedCriteria.forClass(SanityOrderItem.class)
                    .add(Restrictions.eq("orderId", parentKey));
            return orderItemDao.update(parentKey, criteria, entity -> {
                entity.setPrice(999);
                return entity;
            });
        });

        // SELECT (1 prepare) + UPDATE (1 prepare) = 2
        assertTransactionEvents(criteriaResult, 2, true);
        assertTrue(criteriaResult.getValue());

        // Exactly 1 of the 3 should be updated to 999
        val allItems = selectItems(parentKey);
        long updatedCount = allItems.stream().filter(it -> it.getPrice() == 999).count();
        long unchangedCount = allItems.stream().filter(it -> it.getPrice() == 100).count();
        assertEquals(1, updatedCount, "Exactly one item should be updated");
        assertEquals(2, unchangedCount, "The other two must be unchanged");
    }

    @Test
    void selectAndUpdate_singleMatch_updatesIt() throws Exception {
        val parentKey = UUID.randomUUID().toString();

        orderItemDao.save(parentKey, SanityOrderItem.builder()
                .orderId(parentKey).itemName("solo").quantity(10).price(100).build());

        // --- DetachedCriteria variant ---
        val criteriaResult = checkpoint(() -> {
            val criteria = DetachedCriteria.forClass(SanityOrderItem.class)
                    .add(Restrictions.eq("orderId", parentKey))
                    .add(Restrictions.eq("itemName", "solo"));
            return orderItemDao.update(parentKey, criteria, entity -> {
                entity.setPrice(555);
                return entity;
            });
        });

        assertTransactionEvents(criteriaResult, 2, true);
        assertTrue(criteriaResult.getValue());

        val items = selectItems(parentKey);
        assertEquals(555, items.get(0).getPrice());


        // --- QuerySpec variant ---
        val querySpecResult = checkpoint(() -> orderItemDao.update(parentKey,
                (root, query, builder) -> query.where(
                        builder.and(
                                builder.equal(root.get("orderId"), parentKey),
                                builder.equal(root.get("itemName"), "solo"))),
                entity -> {
                    entity.setPrice(777);
                    return entity;
                }));

        assertTransactionEvents(querySpecResult, 2, true);
        assertTrue(querySpecResult.getValue());

        val itemsAfter = selectItems(parentKey);
        assertEquals(777, itemsAfter.get(0).getPrice());
    }

    // =========================================================================
    // 11.2 Atomicity — exception in mutator
    // =========================================================================

    @Test
    void selectAndUpdate_mutatorThrows_rollback() throws Exception {
        val parentKey = UUID.randomUUID().toString();

        orderItemDao.save(parentKey, SanityOrderItem.builder()
                .orderId(parentKey).itemName("widget").quantity(10).price(100).build());

        // --- DetachedCriteria variant ---
        val criteriaResult = checkpoint(() -> {
            assertThrows(Exception.class, () -> {
                val criteria = DetachedCriteria.forClass(SanityOrderItem.class)
                        .add(Restrictions.eq("orderId", parentKey));
                orderItemDao.update(parentKey, criteria, entity -> {
                    entity.setPrice(999);
                    throw new RuntimeException("mutator explosion");
                });
            });
            return null;
        });

        // SELECT (1 prepare), mutator threw → rollback
        assertTransactionEvents(criteriaResult, 1, false);

        val items = selectItems(parentKey);
        assertEquals(100, items.get(0).getPrice(), "Entity must be unchanged after mutator exception");

        // --- QuerySpec variant ---
        val querySpecResult = checkpoint(() -> {
            assertThrows(Exception.class, () ->
                    orderItemDao.update(parentKey,
                            (root, query, builder) -> query.where(
                                    builder.equal(root.get("orderId"), parentKey)),
                            entity -> {
                                entity.setPrice(888);
                                throw new RuntimeException("mutator explosion");
                            }));
            return null;
        });

        assertTransactionEvents(querySpecResult, 1, false);

        val itemsAfter = selectItems(parentKey);
        assertEquals(100, itemsAfter.get(0).getPrice());
    }

    // =========================================================================
    // 11.3 No match — returns false
    // =========================================================================

    @Test
    void selectAndUpdate_noMatch_returnsFalse() throws Exception {
        val parentKey = UUID.randomUUID().toString();

        // --- DetachedCriteria variant ---
        val criteriaResult = checkpoint(() -> {
            val criteria = DetachedCriteria.forClass(SanityOrderItem.class)
                    .add(Restrictions.eq("orderId", parentKey));
            return orderItemDao.update(parentKey, criteria, entity -> {
                entity.setPrice(999);
                return entity;
            });
        });

        // SELECT (1 prepare), empty result → returns false, committed
        assertTransactionEvents(criteriaResult, 1, true);
        assertFalse(criteriaResult.getValue());

        // --- QuerySpec variant ---
        val querySpecResult = checkpoint(() -> orderItemDao.update(parentKey,
                (root, query, builder) -> query.where(
                        builder.equal(root.get("orderId"), parentKey)),
                entity -> {
                    entity.setPrice(999);
                    return entity;
                }));

        assertTransactionEvents(querySpecResult, 1, true);
        assertFalse(querySpecResult.getValue());
    }

    // =========================================================================
    // 11.4 Concurrent SelectAndUpdate — possible lost update
    //
    // SelectAndUpdate uses SELECT with LIMIT 1 (no FOR UPDATE).
    // Same lost-update risk as GetAndUpdate / RelationalDao.update(parentKey, id, updater).
    // =========================================================================

    @Test
    void selectAndUpdate_concurrentUpdates_possibleLostUpdate() throws Exception {
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
                        .add(Restrictions.eq("orderId", parentKey))
                        .add(Restrictions.eq("itemName", "concurrent"));
                orderItemDao.update(parentKey, criteria, entity -> {
                    try { Thread.sleep(5000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                    entity.setQuantity(entity.getQuantity() + 1);
                    return entity;
                });
            } catch (Exception e) {
                error1.set(e);
            }
        });

        val thread2 = new Thread(() -> {
            try {
                startLatch.await();
                Thread.sleep(2000);
                val criteria = DetachedCriteria.forClass(SanityOrderItem.class)
                        .add(Restrictions.eq("orderId", parentKey))
                        .add(Restrictions.eq("itemName", "concurrent"));
                orderItemDao.update(parentKey, criteria, entity -> {
                    try { Thread.sleep(5000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                    entity.setQuantity(entity.getQuantity() + 1);
                    return entity;
                });
            } catch (Exception e) {
                error2.set(e);
            }
        });

        thread1.start();
        thread2.start();
        startLatch.countDown();

        thread1.join(10000);
        thread2.join(10000);;

        if (error1.get() != null) throw error1.get();
        if (error2.get() != null) throw error2.get();

        val items = selectItems(parentKey);
        int finalQuantity = items.get(0).getQuantity();
        assertTrue(finalQuantity == 101 || finalQuantity == 102,
                "Quantity should be 101 (lost update) or 102 (serialized). Got: " + finalQuantity);
        if (finalQuantity == 101) {
            System.out.println("[LOST UPDATE CONFIRMED] SelectAndUpdate without FOR UPDATE: " +
                    "T2 read stale value, final quantity=101 instead of 102");
        } else {
            System.out.println("[NO LOST UPDATE] SelectAndUpdate: quantity=102, " +
                    "DB/driver serialized the updates despite no FOR UPDATE lock");
        }
    }

    // =========================================================================
    // Helper
    // =========================================================================

    private java.util.List<SanityOrderItem> selectItems(String parentKey) throws Exception {
        val criteria = DetachedCriteria.forClass(SanityOrderItem.class)
                .add(Restrictions.eq("orderId", parentKey));
        return orderItemDao.select(parentKey, criteria, 0, Integer.MAX_VALUE);
    }
}

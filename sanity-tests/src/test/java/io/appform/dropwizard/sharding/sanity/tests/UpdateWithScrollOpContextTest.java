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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for the {@code UpdateWithScroll} opContext.
 *
 * <p>Scrolls through entities matching criteria, applies mutator to each,
 * persists via updater. Stops when no more results OR {@code updateNext}
 * returns false. Returns false early if entity or mutator result is null.
 *
 * <p>Used via:
 * <ul>
 *   <li>{@code LockedContext.update(RelationalDao, DetachedCriteria, updater, updateNext)}</li>
 *   <li>{@code LockedContext.update(RelationalDao, QuerySpec, updater, updateNext)}</li>
 * </ul>
 *
 * <p>These execute WITHIN a LockedContext transaction — the scroll-update
 * is part of the same transaction as the parent lock.
 */
class UpdateWithScrollOpContextTest extends SanityTestBase {

    @BeforeEach
    void cleanTables() throws Exception {
        truncateAllTables();
    }

    // -------------------------------------------------------------------------
    // 13.1 Scrolls and updates all matching entities
    // -------------------------------------------------------------------------

    @Test
    void scrollUpdate_allMatchingUpdated() throws Exception {
        val orderId = UUID.randomUUID().toString();

        // Save parent order
        orderLookupDao.save(SanityOrder.builder()
                .orderId(orderId).customerId("c").amount(100).build());

        // Save 50 child items
        for (int i = 1; i <= 50; i++) {
            orderItemDao.save(orderId, SanityOrderItem.builder()
                    .orderId(orderId).itemName("scroll-item-" + i).quantity(10).price(100).build());
        }

        val criteria = DetachedCriteria.forClass(SanityOrderItem.class)
                .add(Restrictions.eq("orderId", orderId));

        val result = checkpoint(() ->
                orderLookupDao.lockAndGetExecutor(orderId)
                        .update(orderItemDao, criteria,
                                entity -> { entity.setPrice(999); return entity; },
                                () -> true)  // continue for all
                        .execute()
        );

//        1 select for update + 1 select on orderItem + 50 updates
        assertTransactionEvents(result, 52, true);

        val items = selectItems(orderId);
        assertEquals(50, items.size());
        assertTrue(items.stream().allMatch(it -> it.getPrice() == 999),
                "All 50 items must be updated to price=999");
    }

    // -------------------------------------------------------------------------
    // 13.2 Early stop via updateNext=false
    // -------------------------------------------------------------------------

    @Test
    void scrollUpdate_earlyStop_firstNUpdated() throws Exception {
        val orderId = UUID.randomUUID().toString();

        orderLookupDao.save(SanityOrder.builder()
                .orderId(orderId).customerId("c").amount(100).build());

        for (int i = 1; i <= 10; i++) {
            orderItemDao.save(orderId, SanityOrderItem.builder()
                    .orderId(orderId).itemName("item-" + i).quantity(10).price(100).build());
        }

        val counter = new AtomicInteger(0);
        val criteria = DetachedCriteria.forClass(SanityOrderItem.class)
                .add(Restrictions.eq("orderId", orderId));

        val result = checkpoint(() ->
                orderLookupDao.lockAndGetExecutor(orderId)
                        .update(orderItemDao, criteria,
                                entity -> { entity.setPrice(999); return entity; },
                                () -> counter.incrementAndGet() < 5)  // stop after 5
                        .execute()
        );

        assertTransactionEvents(result, 7, true);

        val items = selectItems(orderId);
        long updatedCount = items.stream().filter(it -> it.getPrice() == 999).count();
        long unchangedCount = items.stream().filter(it -> it.getPrice() == 100).count();
        assertEquals(5, updatedCount, "First 5 items should be updated");
        assertEquals(5, unchangedCount, "Remaining 5 should be unchanged");
    }

    // -------------------------------------------------------------------------
    // 13.3 Atomicity — exception during scroll (CRITICAL)
    // -------------------------------------------------------------------------

    @Test
    void scrollUpdate_exceptionDuringScroll_rollsBackAll() throws Exception {
        val orderId = UUID.randomUUID().toString();

        orderLookupDao.save(SanityOrder.builder()
                .orderId(orderId).customerId("c").amount(100).build());

        for (int i = 1; i <= 10; i++) {
            orderItemDao.save(orderId, SanityOrderItem.builder()
                    .orderId(orderId).itemName("item-" + i).quantity(10).price(100).build());
        }

        val counter = new AtomicInteger(0);
        val criteria = DetachedCriteria.forClass(SanityOrderItem.class)
                .add(Restrictions.eq("orderId", orderId));

        val result = checkpoint(() -> {
            assertThrows(RuntimeException.class, () ->
                    orderLookupDao.lockAndGetExecutor(orderId)
                            .update(orderItemDao, criteria,
                                    entity -> {
                                        int count = counter.incrementAndGet();
                                        entity.setPrice(999);
                                        if (count == 6) {
                                            throw new RuntimeException("explosion at entity #6");
                                        }
                                        return entity;
                                    },
                                    () -> true)
                            .execute());
            return null;
        });

//        no update is executed due to early exception and rollback
        assertTransactionEvents(result, 2, false);

        // ALL 10 must be unchanged (full rollback, including items 1-5 that were updated before #6 threw)
        val items = selectItems(orderId);
        assertTrue(items.stream().allMatch(it -> it.getPrice() == 100),
                "All 10 items must be unchanged after exception (full rollback)");
    }

    // -------------------------------------------------------------------------
    // 13.4 Memory efficiency — TODO
    // -------------------------------------------------------------------------

    // TODO: Scroll 10,000 entities — verify no OOM, reasonable memory usage.
    //       ScrollableResults processes one row at a time (no full list in memory).
    //       Need to verify Hibernate doesn't cache all entities in the Session.

    // -------------------------------------------------------------------------
    // 13.5 Concurrent scroll-update — possible lost update
    // -------------------------------------------------------------------------

    @Test
    void scrollUpdate_concurrentUpdates_possibleLostUpdate() throws Exception {
        val orderId = UUID.randomUUID().toString();

        orderLookupDao.save(SanityOrder.builder()
                .orderId(orderId).customerId("c").amount(100).build());

        orderItemDao.save(orderId, SanityOrderItem.builder()
                .orderId(orderId).itemName("concurrent").quantity(100).price(10).build());

        val error1 = new AtomicReference<Exception>();
        val error2 = new AtomicReference<Exception>();
        val startLatch = new CountDownLatch(1);

        val criteria = DetachedCriteria.forClass(SanityOrderItem.class)
                .add(Restrictions.eq("orderId", orderId));

        val thread1 = new Thread(() -> {
            try {
                startLatch.await();
                orderLookupDao.lockAndGetExecutor(orderId)
                        .update(orderItemDao, criteria,
                                entity -> {
                                    try { Thread.sleep(5000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                                    entity.setQuantity(entity.getQuantity() + 1);
                                    return entity;
                                },
                                () -> true)
                        .execute();
            } catch (Exception e) { error1.set(e); }
        });

        val thread2 = new Thread(() -> {
            try {
                startLatch.await();
                Thread.sleep(2000);
                orderLookupDao.lockAndGetExecutor(orderId)
                        .update(orderItemDao, criteria,
                                entity -> {
                                    try { Thread.sleep(5000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                                    entity.setQuantity(entity.getQuantity() + 1);
                                    return entity;
                                },
                                () -> true)
                        .execute();
            } catch (Exception e) { error2.set(e); }
        });

        thread1.start();
        thread2.start();
        startLatch.countDown();
        thread1.join(15000);
        thread2.join(15000);

        if (error1.get() != null) throw error1.get();
        if (error2.get() != null) throw error2.get();

        val items = selectItems(orderId);
        int finalQuantity = items.get(0).getQuantity();
        // lockAndGetExecutor uses SELECT FOR UPDATE on the PARENT (SanityOrder).
        // But the child entity (SanityOrderItem) is NOT locked — the scroll-update
        // on the child doesn't acquire a FOR UPDATE lock.
        // However, since both threads lock the same parent first, the second thread
        // blocks until the first completes. So the updates should be serialized.
        assertTrue(finalQuantity == 101 || finalQuantity == 102,
                "Quantity should be 101 (lost update) or 102 (serialized via parent lock). Got: " + finalQuantity);
        if (finalQuantity == 102) {
            System.out.println("[SERIALIZED VIA PARENT LOCK] UpdateWithScroll: quantity=102. " +
                    "Parent SELECT FOR UPDATE serialized the child updates.");
        } else {
            System.out.println("[LOST UPDATE] UpdateWithScroll: quantity=101. " +
                    "Parent lock did not prevent child entity lost update.");
        }
    }

    // -------------------------------------------------------------------------
    // 13.6 Mutator returns null — partial update risk (CRITICAL)
    //
    // Same risk as UpdateAll (12.3): returns false early, but transaction commits.
    // Entities processed before null are updated; remaining are not.
    // -------------------------------------------------------------------------

    @Test
    void scrollUpdate_mutatorReturnsNull_partialUpdateRisk() throws Exception {
        val orderId = UUID.randomUUID().toString();

        orderLookupDao.save(SanityOrder.builder()
                .orderId(orderId).customerId("c").amount(100).build());

        for (int i = 1; i <= 5; i++) {
            orderItemDao.save(orderId, SanityOrderItem.builder()
                    .orderId(orderId).itemName("item-" + i).quantity(i).price(100).build());
        }

        val counter = new AtomicInteger(0);
        val criteria = DetachedCriteria.forClass(SanityOrderItem.class)
                .add(Restrictions.eq("orderId", orderId));

        val result = checkpoint(() ->
                orderLookupDao.lockAndGetExecutor(orderId)
                        .update(orderItemDao, criteria,
                                entity -> {
                                    int count = counter.incrementAndGet();
                                    if (count == 3) return null;
                                    entity.setPrice(999);
                                    return entity;
                                },
                                () -> true)
                        .execute()
        );

        // Transaction commits (execute() returns the parent entity, not the update result)
        assertTransactionEvents(result, 4, true);

        val items = selectItems(orderId);
        long updatedCount = items.stream().filter(it -> it.getPrice() == 999).count();
        long unchangedCount = items.stream().filter(it -> it.getPrice() == 100).count();

        System.out.println("[PARTIAL UPDATE RISK] UpdateWithScroll mutator returned null: " +
                "updated=" + updatedCount + ", unchanged=" + unchangedCount +
                ". If updated > 0 AND unchanged > 0, this is DATA CORRUPTION.");
    }

    // -------------------------------------------------------------------------
    // Helper
    // -------------------------------------------------------------------------

    private java.util.List<SanityOrderItem> selectItems(String orderId) throws Exception {
        val criteria = DetachedCriteria.forClass(SanityOrderItem.class)
                .add(Restrictions.eq("orderId", orderId));
        return orderItemDao.select(orderId, criteria, 0, Integer.MAX_VALUE);
    }
}

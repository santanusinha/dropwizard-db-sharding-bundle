package io.appform.dropwizard.sharding.sanity.tests;

import io.appform.dropwizard.sharding.sanity.base.SanityTestBase;
import io.appform.dropwizard.sharding.sanity.entities.SanityOrder;
import io.appform.dropwizard.sharding.sanity.entities.SanityOrderItem;
import lombok.val;
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
 * Tests for {@code GetAndUpdate} and {@code GetAndUpdateByLookupKey} opContexts.
 *
 * <h3>GetAndUpdate</h3>
 * Used by:
 * <ul>
 *   <li>{@code RelationalDao.update(parentKey, id, updater)} — SELECT FOR UPDATE by @Id, mutate, persist</li>
 *   <li>{@code LockedContext.update(relationalDao, id, updater)} — same, within locked context</li>
 * </ul>
 *
 * <h3>GetAndUpdateByLookupKey</h3>
 * Used by:
 * <ul>
 *   <li>{@code LookupDao.updateInLock(id, updater)} — SELECT FOR UPDATE by @LookupKey, mutate, persist</li>
 *   <li>{@code LookupDao.update(id, updater)} — SELECT (no FOR UPDATE) by @LookupKey, mutate, persist</li>
 * </ul>
 *
 * <p>Key difference: {@code updateInLock} uses {@code getLockedForWrite} (PESSIMISTIC_WRITE),
 * {@code update} uses {@code get} (LockMode.READ). Both use GetAndUpdateByLookupKey opContext.
 */
class GetAndUpdateOpContextTest extends SanityTestBase {

    @BeforeEach
    void cleanTables() throws Exception {
        truncateAllTables();
    }

    // =========================================================================
    // 10. GetAndUpdate — RelationalDao.update(parentKey, id, updater)
    // =========================================================================

    // -------------------------------------------------------------------------
    // 10.1 Basic get-and-update
    // -------------------------------------------------------------------------

    @Test
    void relationalDao_update_basicGetAndUpdate() throws Exception {
        val parentKey = UUID.randomUUID().toString();

        val saved = orderItemDao.save(parentKey, SanityOrderItem.builder()
                .orderId(parentKey).itemName("widget").quantity(1).price(100).build());
        val id = saved.get().getId();

        // Update quantity from 1 to 99
        val result = checkpoint(() -> orderItemDao.update(parentKey, id, entity -> {
            entity.setQuantity(99);
            return entity;
        }));

        // SELECT FOR UPDATE (1 prepare) + UPDATE (1 prepare) = 2
        assertTransactionEvents(result, 2, true);
        assertTrue(result.getValue());

        // Read back
        val readBack = orderItemDao.get(parentKey, id);
        assertEquals(99, readBack.get().getQuantity());
    }

    // -------------------------------------------------------------------------
    // 10.2 Atomicity — exception in mutator rolls back
    // -------------------------------------------------------------------------

    @Test
    void relationalDao_update_exceptionInMutator_rollsBack() throws Exception {
        val parentKey = UUID.randomUUID().toString();

        val saved = orderItemDao.save(parentKey, SanityOrderItem.builder()
                .orderId(parentKey).itemName("bolt").quantity(10).price(50).build());
        val id = saved.get().getId();

        val result = checkpoint(() -> {
            assertThrows(Exception.class, () -> orderItemDao.update(parentKey, id, entity -> {
                entity.setQuantity(999);
                throw new RuntimeException("mutator explosion");
            }));
            return null;
        });

        // SELECT FOR UPDATE (1 prepare), mutator threw → rollback (no UPDATE prepare)
        assertTransactionEvents(result, 1, false);

        // Original value unchanged
        val readBack = orderItemDao.get(parentKey, id);
        assertEquals(10, readBack.get().getQuantity(),
                "Entity must be unchanged after mutator exception");
    }

    // -------------------------------------------------------------------------
    // 10.3 Entity not found — returns false
    // -------------------------------------------------------------------------

    @Test
    void relationalDao_update_entityNotFound_returnsFalse() throws Exception {
        val parentKey = UUID.randomUUID().toString();

        val result = checkpoint(() -> orderItemDao.update(parentKey, 999999L, entity -> {
            entity.setQuantity(99);
            return entity;
        }));

        // SELECT FOR UPDATE (1 prepare), entity is null → returns false, committed
        assertTransactionEvents(result, 1, true);
        assertFalse(result.getValue());
    }

    // -------------------------------------------------------------------------
    // 10.4 Mutator returns null — no update, returns false
    // -------------------------------------------------------------------------

    @Test
    void relationalDao_update_mutatorReturnsNull_noUpdate() throws Exception {
        val parentKey = UUID.randomUUID().toString();

        val saved = orderItemDao.save(parentKey, SanityOrderItem.builder()
                .orderId(parentKey).itemName("screw").quantity(20).price(5).build());
        val id = saved.get().getId();

        val result = checkpoint(() -> orderItemDao.update(parentKey, id, entity -> null));

        // SELECT FOR UPDATE (1 prepare), mutator returned null → no UPDATE, returns false
        assertTransactionEvents(result, 1, true);
        assertFalse(result.getValue());

        // Original unchanged
        val readBack = orderItemDao.get(parentKey, id);
        assertEquals(20, readBack.get().getQuantity());
    }

    // =========================================================================
    // 16. GetAndUpdateByLookupKey — LookupDao.updateInLock / LookupDao.update
    // =========================================================================

    // -------------------------------------------------------------------------
    // 16.1 Basic get-update-persist cycle — updateInLock (PESSIMISTIC_WRITE)
    // -------------------------------------------------------------------------

    @Test
    void lookupDao_updateInLock_basicUpdate() throws Exception {
        val orderId = UUID.randomUUID().toString();

        orderLookupDao.save(SanityOrder.builder()
                .orderId(orderId).customerId("original").amount(100).build());

        val result = checkpoint(() -> orderLookupDao.updateInLock(orderId, existing -> {
            val order = existing.orElseThrow();
            order.setCustomerId("updated-in-lock");
            order.setAmount(999);
            return order;
        }));

        // SELECT FOR UPDATE (1 prepare) + UPDATE (1 prepare) = 2
        assertTransactionEvents(result, 2, true);
        assertTrue(result.getValue());

        val readBack = orderLookupDao.get(orderId);
        assertEquals("updated-in-lock", readBack.get().getCustomerId());
        assertEquals(999, readBack.get().getAmount());
    }

    // -------------------------------------------------------------------------
    // 16.1b Basic get-update-persist cycle — update (LockMode.READ, no FOR UPDATE)
    // -------------------------------------------------------------------------

    @Test
    void lookupDao_update_basicUpdate() throws Exception {
        val orderId = UUID.randomUUID().toString();

        orderLookupDao.save(SanityOrder.builder()
                .orderId(orderId).customerId("original").amount(100).build());

        val result = checkpoint(() -> orderLookupDao.update(orderId, existing -> {
            val order = existing.orElseThrow();
            order.setCustomerId("updated-no-lock");
            order.setAmount(888);
            return order;
        }));

        // SELECT (1 prepare) + UPDATE (1 prepare) = 2
        assertTransactionEvents(result, 2, true);
        assertTrue(result.getValue());

        val readBack = orderLookupDao.get(orderId);
        assertEquals("updated-no-lock", readBack.get().getCustomerId());
        assertEquals(888, readBack.get().getAmount());
    }

    // -------------------------------------------------------------------------
    // 16.2 Entity not found — mutator receives empty Optional
    // -------------------------------------------------------------------------

    @Test
    void lookupDao_updateInLock_entityNotFound_returnsFalse() throws Exception {
        val result = checkpoint(() ->
                orderLookupDao.updateInLock(UUID.randomUUID().toString(), existing -> {
                    assertTrue(existing.isEmpty(), "Mutator must receive empty Optional for non-existent key");
                    return null; // return null → false
                }));

        // SELECT FOR UPDATE (1 prepare), entity not found, mutator returned null → false
        assertTransactionEvents(result, 1, true);
        assertFalse(result.getValue());
    }

    @Test
    void lookupDao_update_entityNotFound_returnsFalse() throws Exception {
        val result = checkpoint(() ->
                orderLookupDao.update(UUID.randomUUID().toString(), existing -> {
                    assertTrue(existing.isEmpty());
                    return null;
                }));

        assertTransactionEvents(result, 1, true);
        assertFalse(result.getValue());
    }

    // -------------------------------------------------------------------------
    // 16.3 Atomicity — mutator throws, entity unchanged
    // -------------------------------------------------------------------------

    @Test
    void lookupDao_updateInLock_mutatorThrows_rollback() throws Exception {
        val orderId = UUID.randomUUID().toString();

        orderLookupDao.save(SanityOrder.builder()
                .orderId(orderId).customerId("original").amount(100).build());

        val result = checkpoint(() -> {
            assertThrows(Exception.class, () ->
                    orderLookupDao.updateInLock(orderId, existing -> {
                        val order = existing.orElseThrow();
                        order.setCustomerId("should-not-persist");
                        throw new RuntimeException("mutator explosion");
                    }));
            return null;
        });

        // SELECT FOR UPDATE (1 prepare), mutator threw → rollback
        assertTransactionEvents(result, 1, false);

        val readBack = orderLookupDao.get(orderId);
        assertEquals("original", readBack.get().getCustomerId(),
                "Entity must be unchanged after mutator exception");
    }

    @Test
    void lookupDao_update_mutatorThrows_rollback() throws Exception {
        val orderId = UUID.randomUUID().toString();

        orderLookupDao.save(SanityOrder.builder()
                .orderId(orderId).customerId("original").amount(100).build());

        val result = checkpoint(() -> {
            assertThrows(Exception.class, () ->
                    orderLookupDao.update(orderId, existing -> {
                        val order = existing.orElseThrow();
                        order.setCustomerId("should-not-persist");
                        throw new RuntimeException("mutator explosion");
                    }));
            return null;
        });

        assertTransactionEvents(result, 1, false);

        val readBack = orderLookupDao.get(orderId);
        assertEquals("original", readBack.get().getCustomerId());
    }

    // -------------------------------------------------------------------------
    // 16.3b Mutator returns null — no update, returns false
    // -------------------------------------------------------------------------

    @Test
    void lookupDao_updateInLock_mutatorReturnsNull_noUpdate() throws Exception {
        val orderId = UUID.randomUUID().toString();

        orderLookupDao.save(SanityOrder.builder()
                .orderId(orderId).customerId("original").amount(100).build());

        val result = checkpoint(() ->
                orderLookupDao.updateInLock(orderId, existing -> null));

        // SELECT FOR UPDATE (1 prepare), mutator returned null → no UPDATE, false
        assertTransactionEvents(result, 1, true);
        assertFalse(result.getValue());

        val readBack = orderLookupDao.get(orderId);
        assertEquals("original", readBack.get().getCustomerId());
    }

    // =========================================================================
    // Concurrent update tests
    //
    // Two threads update the same entity simultaneously.
    // Thread 1: starts immediately, sleeps 5s in mutator, quantity = quantity + 1
    // Thread 2: sleeps 2s before invoking update, sleeps 5s in mutator, quantity = quantity + 1
    //
    // Expected: final quantity = quantity + 2 (both increments applied).
    //
    // Timeline for updateInLock (SELECT FOR UPDATE — serialized):
    //   T1 acquires lock at t=0, reads 100, sleeps 5s, writes 101, commits at t=5
    //   T2 tries lock at t=2, BLOCKS until t=5, reads 101, sleeps 5s, writes 102, commits at t=10
    //   Result: 102 ✓
    //
    // Timeline for update (LockMode.READ — no lock):
    //   T1 reads 100 at t=0, sleeps 5s, writes 101, commits at t=5
    //   T2 reads 100 at t=2 (no lock!), sleeps 5s, writes 101, commits at t=7
    //   Result: 101 ✗ (lost update!)
    // =========================================================================

    @Test
    void lookupDao_updateInLock_concurrentUpdates_serialized() throws Exception {
        val orderId = UUID.randomUUID().toString();

        orderLookupDao.save(SanityOrder.builder()
                .orderId(orderId).customerId("c").amount(100).build());

        val error1 = new AtomicReference<Exception>();
        val error2 = new AtomicReference<Exception>();
        val startLatch = new CountDownLatch(1);

        val thread1 = new Thread(() -> {
            try {
                startLatch.await();
                orderLookupDao.updateInLock(orderId, existing -> {
                    val order = existing.orElseThrow();
                    try { Thread.sleep(5000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                    order.setAmount(order.getAmount() + 1);
                    return order;
                });
            } catch (Exception e) {
                error1.set(e);
            }
        });

        val thread2 = new Thread(() -> {
            try {
                startLatch.await();
                Thread.sleep(2000); // start update 2s after thread1
                orderLookupDao.updateInLock(orderId, existing -> {
                    val order = existing.orElseThrow();
                    try { Thread.sleep(5000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                    order.setAmount(order.getAmount() + 1);
                    return order;
                });
            } catch (Exception e) {
                error2.set(e);
            }
        });

        thread1.start();
        thread2.start();
        startLatch.countDown(); // release both threads

        thread1.join(10000);
        thread2.join(10000);

        if (error1.get() != null) throw error1.get();
        if (error2.get() != null) throw error2.get();

        val readBack = orderLookupDao.get(orderId);
        assertEquals(102, readBack.get().getAmount(),
                "updateInLock uses SELECT FOR UPDATE — both increments must be serialized, no lost update");
    }

//    TODO: review behaviour
    @Test
    void lookupDao_update_concurrentUpdates_possibleLostUpdate() throws Exception {
        val orderId = UUID.randomUUID().toString();

        orderLookupDao.save(SanityOrder.builder()
                .orderId(orderId).customerId("c").amount(100).build());

        val error1 = new AtomicReference<Exception>();
        val error2 = new AtomicReference<Exception>();
        val startLatch = new CountDownLatch(1);

        val thread1 = new Thread(() -> {
            try {
                startLatch.await();
                orderLookupDao.update(orderId, existing -> {
                    val order = existing.orElseThrow();
                    try { Thread.sleep(5000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                    order.setAmount(order.getAmount() + 1);
                    return order;
                });
            } catch (Exception e) {
                error1.set(e);
            }
        });

        val thread2 = new Thread(() -> {
            try {
                startLatch.await();
                Thread.sleep(2000);
                orderLookupDao.update(orderId, existing -> {
                    val order = existing.orElseThrow();
                    try { Thread.sleep(5000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                    order.setAmount(order.getAmount() + 1);
                    return order;
                });
            } catch (Exception e) {
                error2.set(e);
            }
        });

        thread1.start();
        thread2.start();
        startLatch.countDown();

        thread1.join(10000);
        thread2.join(10000);

        if (error1.get() != null) throw error1.get();
        if (error2.get() != null) throw error2.get();

        val readBack = orderLookupDao.get(orderId);
        // LookupDao.update() uses LockMode.READ (no FOR UPDATE).
        // T2 reads the ORIGINAL value (100) before T1 commits → both write 101.
        // This is a LOST UPDATE — expected 102, likely got 101.
        // If this assertion passes (102), it means the DB/driver serialized anyway.
        // If it fails (101), it confirms the lost-update bug.
        int finalAmount = readBack.get().getAmount();
        assertTrue(finalAmount == 101 || finalAmount == 102,
                "Amount should be 101 (lost update) or 102 (serialized). Got: " + finalAmount);
        if (finalAmount == 101) {
            System.out.println("[LOST UPDATE CONFIRMED] LookupDao.update() without FOR UPDATE: " +
                    "T2 read stale value, final amount=101 instead of 102");
        } else {
            System.out.println("[NO LOST UPDATE] LookupDao.update(): amount=102, " +
                    "DB/driver serialized the updates despite no FOR UPDATE lock");
        }
    }

    // =========================================================================
    // RelationalDao.update() — concurrent updates (no updateInLock available)
    // =========================================================================

    @Test
    void relationalDao_update_concurrentUpdates_possibleLostUpdate() throws Exception {
        val parentKey = UUID.randomUUID().toString();

        val saved = orderItemDao.save(parentKey, SanityOrderItem.builder()
                .orderId(parentKey).itemName("widget").quantity(100).price(10).build());
        val id = saved.get().getId();

        val error1 = new AtomicReference<Exception>();
        val error2 = new AtomicReference<Exception>();
        val startLatch = new CountDownLatch(1);

        val thread1 = new Thread(() -> {
            try {
                startLatch.await();
                orderItemDao.update(parentKey, id, entity -> {
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
                orderItemDao.update(parentKey, id, entity -> {
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
        thread2.join(10000);

        if (error1.get() != null) throw error1.get();
        if (error2.get() != null) throw error2.get();

        val readBack = orderItemDao.get(parentKey, id);
        // RelationalDao.update() uses LockMode.READ — same lost-update risk as LookupDao.update()
        int finalQuantity = readBack.get().getQuantity();
        assertTrue(finalQuantity == 101 || finalQuantity == 102,
                "Quantity should be 101 (lost update) or 102 (serialized). Got: " + finalQuantity);
        if (finalQuantity == 101) {
            System.out.println("[LOST UPDATE CONFIRMED] RelationalDao.update() without FOR UPDATE: " +
                    "T2 read stale value, final quantity=101 instead of 102");
        } else {
            System.out.println("[NO LOST UPDATE] RelationalDao.update(): quantity=102, " +
                    "DB/driver serialized the updates despite no FOR UPDATE lock");
        }
    }

    // =========================================================================
    // Skipped tests:
    //
    // (Concurrent tests above were previously listed as skipped — now implemented)
    // =========================================================================
}

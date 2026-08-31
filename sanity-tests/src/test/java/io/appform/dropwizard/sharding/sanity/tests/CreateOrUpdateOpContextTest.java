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
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@code CreateOrUpdateByLookupKey} and {@code CreateOrUpdate} opContexts.
 *
 * <h3>CreateOrUpdateByLookupKey</h3>
 * Used by:
 * <ul>
 *   <li>{@code LookupDao.createOrUpdate(id, updater, entityGenerator)}</li>
 * </ul>
 * <p>Behavior: SELECT FOR UPDATE by @LookupKey. If null → generator creates + saver persists.
 * If exists → mutator applied, updater persists. Returns the <b>mutator's return value</b>
 * directly (may NOT have auto-generated fields if mutator creates a new object).
 *
 * <h3>CreateOrUpdate (RelationalDao)</h3>
 * Used by:
 * <ul>
 *   <li>{@code RelationalDao.createOrUpdate(parentKey, criteria, updater, entityGenerator)}</li>
 *   <li>{@code LockedContext.createOrUpdate(relationalDao, criteria, updater, entityGenerator)}</li>
 *   <li>{@code LockedContext.createOrUpdate(relationalDao, criteria, updater, Function entityGenerator)}</li>
 *   <li>{@code LockedContext.createOrUpdate(relationalDao, querySpec, updater, entityGenerator)}</li>
 * </ul>
 * <p>Behavior: SELECT FOR UPDATE by criteria. If null → generator creates + saver persists.
 * If exists → mutator applied, updater persists, then <b>re-fetches from DB</b> via getter.
 * Caller gets the fresh DB version with all auto-generated fields — unlike
 * CreateOrUpdateByLookupKey which returns the mutator's return value directly.
 */
class CreateOrUpdateOpContextTest extends SanityTestBase {

    @BeforeEach
    void cleanTables() throws Exception {
        truncateAllTables();
    }

    // =========================================================================
    // 17. CreateOrUpdateByLookupKey — LookupDao.createOrUpdate()
    // =========================================================================

    // -------------------------------------------------------------------------
    // 17.1 Create path — entity does not exist
    //   - Only entityGenerator must be invoked, NOT the updater
    //   - Readback must have a valid generated @Id
    // -------------------------------------------------------------------------

    @Test
    void lookupDao_createOrUpdate_createPath() throws Exception {
        val orderId = UUID.randomUUID().toString();
        val updaterCalled = new AtomicBoolean(false);
        val generatorCalled = new AtomicBoolean(false);

        val result = checkpoint(() -> orderLookupDao.createOrUpdate(
                orderId,
                existing -> {
                    updaterCalled.set(true);
                    return existing;
                },
                () -> {
                    generatorCalled.set(true);
                    return SanityOrder.builder()
                            .orderId(orderId)
                            .customerId("new-customer")
                            .amount(100)
                            .build();
                }
        ));

        // SELECT FOR UPDATE (1) + INSERT (1) = 2 prepares
        assertTransactionEvents(result, 2, true);
        assertTrue(result.getValue().isPresent());
        assertNotNull(result.getValue().get().getId(), "Readback must have a valid generated @Id");
        assertEquals(orderId, result.getValue().get().getOrderId());

        // Verify callback invocations
        assertTrue(generatorCalled.get(), "entityGenerator must be invoked on create path");
        assertFalse(updaterCalled.get(), "updater must NOT be invoked on create path");

        // Readback — verify valid generated ID
        val readBack = orderLookupDao.get(orderId);
        assertTrue(readBack.isPresent());
        assertNotNull(readBack.get().getId(), "Readback must have a valid generated @Id");
        assertTrue(readBack.get().getId() > 0, "Generated @Id must be positive");
        assertEquals("new-customer", readBack.get().getCustomerId());
        assertEquals(100, readBack.get().getAmount());
    }

    // -------------------------------------------------------------------------
    // 17.2 Update path — entity exists
    //   - Only updater must be invoked, NOT the entityGenerator
    //   - Readback must have the same @Id as the original
    //
    // NOTE: CreateOrUpdateByLookupKey returns the mutator's return value directly.
    // If the mutator returns the same managed entity (mutated in-place), it works.
    // But if the mutator creates a NEW object, auto-generated fields like @Id
    // may not be populated in the returned value — because the return bypasses
    // the DB re-fetch.
    // -------------------------------------------------------------------------

    @Test
    void lookupDao_createOrUpdate_updatePath() throws Exception {
        val orderId = UUID.randomUUID().toString();
        val updaterCalled = new AtomicBoolean(false);
        val generatorCalled = new AtomicBoolean(false);

        val saved = orderLookupDao.save(SanityOrder.builder()
                .orderId(orderId).customerId("original").amount(100).build());
        val originalId = saved.get().getId();

        val result = checkpoint(() -> orderLookupDao.createOrUpdate(
                orderId,
                existing -> {
//                    try {
//                        Thread.sleep(3000);
//                    } catch (InterruptedException e) {
//                        throw new RuntimeException(e);
//                    }
                    updaterCalled.set(true);
                    existing.setCustomerId("updated");
                    existing.setAmount(999);
                    return existing;
                },
                () -> {
                    generatorCalled.set(true);
                    throw new RuntimeException("generator should not be called on update path");
                }
        ));

        // SELECT FOR UPDATE (1) + UPDATE (dirty check) (1) = 2 prepares
        assertTransactionEvents(result, 2, true);
        assertTrue(result.getValue().isPresent());

        // Verify callback invocations
        assertTrue(updaterCalled.get(), "updater must be invoked on update path");
        assertFalse(generatorCalled.get(), "entityGenerator must NOT be invoked on update path");

        // CAVEAT: returned value is the mutator's return, not a re-fetch.
        assertEquals("updated", result.getValue().get().getCustomerId());

        // Readback — same @Id, updated fields
        val readBack = orderLookupDao.get(orderId);
        assertNotNull(readBack.get().getId());
//        TODO: re-enable this assertion once fetch from DB behaviour is confirmed
//        assertEquals(result.getValue().get().getUpdatedAt(), readBack.get().getUpdatedAt(), "mismatch in updatedAt timestamps");
        assertEquals(originalId, readBack.get().getId(), "Readback must have the same @Id");
        assertEquals("updated", readBack.get().getCustomerId());
        assertEquals(999, readBack.get().getAmount());
    }

    // -------------------------------------------------------------------------
    // 17.4 Atomicity — entityGenerator throws
    // -------------------------------------------------------------------------

    @Test
    void lookupDao_createOrUpdate_generatorThrows_nothingSaved() throws Exception {
        val orderId = UUID.randomUUID().toString();
        val updaterCalled = new AtomicBoolean(false);

        val result = checkpoint(() -> {
            assertThrows(Exception.class, () -> orderLookupDao.createOrUpdate(
                    orderId,
                    existing -> {
                        updaterCalled.set(true);
                        return existing;
                    },
                    () -> { throw new RuntimeException("generator explosion"); }
            ));
            return null;
        });

        // SELECT FOR UPDATE (1), generator threw → rollback
        assertTransactionEvents(result, 1, false);
        assertFalse(updaterCalled.get(), "updater must NOT be invoked when generator throws");

        val readBack = orderLookupDao.get(orderId);
        assertTrue(readBack.isEmpty(), "Nothing should be saved after generator exception");
    }

    // -------------------------------------------------------------------------
    // 17.5 Atomicity — updater throws after lock acquired
    // -------------------------------------------------------------------------

    @Test
    void lookupDao_createOrUpdate_updaterThrows_entityUnchanged() throws Exception {
        val orderId = UUID.randomUUID().toString();
        val generatorCalled = new AtomicBoolean(false);

        orderLookupDao.save(SanityOrder.builder()
                .orderId(orderId).customerId("original").amount(100).build());

        val result = checkpoint(() -> {
            assertThrows(Exception.class, () -> orderLookupDao.createOrUpdate(
                    orderId,
                    existing -> {
                        existing.setCustomerId("should-not-persist");
                        throw new RuntimeException("updater explosion");
                    },
                    () -> {
                        generatorCalled.set(true);
                        throw new RuntimeException("should not be called");
                    }
            ));
            return null;
        });

        // SELECT FOR UPDATE (1), updater threw → rollback
        assertTransactionEvents(result, 1, false);
        assertFalse(generatorCalled.get(), "entityGenerator must NOT be invoked on update path");

        val readBack = orderLookupDao.get(orderId);
        assertEquals("original", readBack.get().getCustomerId(),
                "Entity must be unchanged after updater exception");
    }

    // =========================================================================
    // 18. CreateOrUpdate — RelationalDao.createOrUpdate()
    //
    // Key difference from CreateOrUpdateByLookupKey:
    // On update path, after mutator + updater, it RE-FETCHES from DB via
    // getter.apply(criteria). Caller gets the fresh DB version with all
    // auto-generated fields. CreateOrUpdateByLookupKey returns the mutator's
    // return value directly (no re-fetch).
    // =========================================================================

    // -------------------------------------------------------------------------
    // 18.1 Create path
    //   - Only entityGenerator invoked, NOT updater
    //   - Returned entity must have generated @Id
    // -------------------------------------------------------------------------

    @Test
    void relationalDao_createOrUpdate_createPath() throws Exception {
        val parentKey = UUID.randomUUID().toString();
        val updaterCalled = new AtomicBoolean(false);
        val generatorCalled = new AtomicBoolean(false);

        val criteria = DetachedCriteria.forClass(SanityOrderItem.class)
                .add(Restrictions.eq("orderId", parentKey))
                .add(Restrictions.eq("itemName", "widget"));

        val result = checkpoint(() -> orderItemDao.createOrUpdate(
                parentKey,
                criteria,
                existing -> {
                    updaterCalled.set(true);
                    return existing;
                },
                () -> {
                    generatorCalled.set(true);
                    return SanityOrderItem.builder()
                            .orderId(parentKey)
                            .itemName("widget")
                            .quantity(5)
                            .price(200)
                            .build();
                }
        ));

        // SELECT FOR UPDATE (1) + INSERT (1) = 2 prepares
        assertTransactionEvents(result, 2, true);
        assertTrue(result.getValue().isPresent());

        // Verify callback invocations
        assertTrue(generatorCalled.get(), "entityGenerator must be invoked on create path");
        assertFalse(updaterCalled.get(), "updater must NOT be invoked on create path");

        // Returned entity must have generated @Id
        assertNotNull(result.getValue().get().getId(), "Created entity must have generated @Id");
        assertTrue(result.getValue().get().getId() > 0);

        // Readback
        val items = selectItems(parentKey);
        assertEquals(1, items.size());
        assertNotNull(items.get(0).getId());
        assertEquals("widget", items.get(0).getItemName());
    }

    // -------------------------------------------------------------------------
    // 18.2 Update path with re-fetch
    //   - Only updater invoked, NOT entityGenerator
    //   - Returned entity is the re-fetched (fresh) version with @Id (expected but not working)
    // -------------------------------------------------------------------------

    @Test
    void relationalDao_createOrUpdate_updatePath_returnsFreshFromDb() throws Exception {
        val parentKey = UUID.randomUUID().toString();
        val updaterCalled = new AtomicBoolean(false);
        val generatorCalled = new AtomicBoolean(false);

        val saved = orderItemDao.save(parentKey, SanityOrderItem.builder()
                .orderId(parentKey).itemName("bolt").quantity(10).price(50).build());
        val originalId = saved.get().getId();

        val readBack1 = orderItemDao.get(parentKey, originalId);

        val criteria = DetachedCriteria.forClass(SanityOrderItem.class)
                .add(Restrictions.eq("orderId", parentKey))
                .add(Restrictions.eq("itemName", "bolt"));

        val result = checkpoint(() -> orderItemDao.createOrUpdate(
                parentKey,
                criteria,
                existing -> {
//                    try {
//                        Thread.sleep(3000);
//                    } catch (InterruptedException e) {
//                        throw new RuntimeException(e);
//                    }
                    updaterCalled.set(true);
                    existing.setQuantity(99);
                    return existing;
                },
                () -> {
                    generatorCalled.set(true);
                    throw new RuntimeException("should not be called");
                }
        ));

        // SELECT FOR UPDATE (1) + UPDATE (dirty) (1) + re-fetch SELECT (1) = 3 prepares
        assertTransactionEvents(result, 3, true);
        assertTrue(result.getValue().isPresent());

        // Verify callback invocations
        assertTrue(updaterCalled.get(), "updater must be invoked on update path");
        assertFalse(generatorCalled.get(), "entityGenerator must NOT be invoked on update path");

        // Returned value is the RE-FETCHED version — has @Id and updated fields
        val returned = result.getValue().get();
        assertEquals(originalId, returned.getId(), "Re-fetched entity must have the same @Id");
        assertEquals(99, returned.getQuantity(), "Re-fetched entity must have updated quantity");

        // Readback
        val readBack = orderItemDao.get(parentKey, originalId);
//        TODO: re-enable this assertion once fetch from DB behaviour is confirmed
//        assertEquals(result.getValue().get().getUpdatedAt(), readBack.get().getUpdatedAt(), "mismatch in updatedAt timestamps");
        assertNotNull(readBack.get().getId());
        assertEquals(99, readBack.get().getQuantity());
    }

    // -------------------------------------------------------------------------
    // 18.4 Atomicity on failure — mutator throws, entity unchanged
    // -------------------------------------------------------------------------

    @Test
    void relationalDao_createOrUpdate_updaterThrows_entityUnchanged() throws Exception {
        val parentKey = UUID.randomUUID().toString();
        val generatorCalled = new AtomicBoolean(false);

        val saved = orderItemDao.save(parentKey, SanityOrderItem.builder()
                .orderId(parentKey).itemName("nut").quantity(20).price(5).build());
        val originalId = saved.get().getId();

        val criteria = DetachedCriteria.forClass(SanityOrderItem.class)
                .add(Restrictions.eq("orderId", parentKey))
                .add(Restrictions.eq("itemName", "nut"));

        val result = checkpoint(() -> {
            assertThrows(Exception.class, () -> orderItemDao.createOrUpdate(
                    parentKey,
                    criteria,
                    existing -> {
                        existing.setQuantity(999);
                        throw new RuntimeException("updater explosion");
                    },
                    () -> {
                        generatorCalled.set(true);
                        throw new RuntimeException("should not be called");
                    }
            ));
            return null;
        });

        // SELECT FOR UPDATE (1), updater threw → rollback
        assertTransactionEvents(result, 1, false);
        assertFalse(generatorCalled.get(), "entityGenerator must NOT be invoked on update path");

        val items = selectItems(parentKey);
        assertEquals(20, items.get(0).getQuantity(),
                "Entity must be unchanged after updater exception");
        assertEquals(originalId, items.get(0).getId(), "@Id must be unchanged");
    }

    // -------------------------------------------------------------------------
    // 18.4b Atomicity — generator throws on create path
    // -------------------------------------------------------------------------

    @Test
    void relationalDao_createOrUpdate_generatorThrows_nothingSaved() throws Exception {
        val parentKey = UUID.randomUUID().toString();
        val updaterCalled = new AtomicBoolean(false);

        val criteria = DetachedCriteria.forClass(SanityOrderItem.class)
                .add(Restrictions.eq("orderId", parentKey))
                .add(Restrictions.eq("itemName", "nonexistent"));

        val result = checkpoint(() -> {
            assertThrows(Exception.class, () -> orderItemDao.createOrUpdate(
                    parentKey,
                    criteria,
                    existing -> {
                        updaterCalled.set(true);
                        return existing;
                    },
                    () -> { throw new RuntimeException("generator explosion"); }
            ));
            return null;
        });

        // SELECT FOR UPDATE (1), generator threw → rollback
        assertTransactionEvents(result, 1, false);
        assertFalse(updaterCalled.get(), "updater must NOT be invoked when generator throws");

        val items = selectItems(parentKey);
        assertTrue(items.isEmpty(), "Nothing should be saved after generator exception");
    }

    // -------------------------------------------------------------------------
    // 18.5 Multiple rows match criteria — NonUniqueResultException
    // -------------------------------------------------------------------------

    @Test
    void relationalDao_createOrUpdate_multipleRowsMatchCriteria_throws() throws Exception {
        val parentKey = UUID.randomUUID().toString();
        val updaterCalled = new AtomicBoolean(false);
        val generatorCalled = new AtomicBoolean(false);

        orderItemDao.save(parentKey, SanityOrderItem.builder()
                .orderId(parentKey).itemName("item-1").quantity(1).price(10).build());
        orderItemDao.save(parentKey, SanityOrderItem.builder()
                .orderId(parentKey).itemName("item-2").quantity(2).price(20).build());

        // Criteria matches both items (only filters by orderId)
        val criteria = DetachedCriteria.forClass(SanityOrderItem.class)
                .add(Restrictions.eq("orderId", parentKey));

        val result = checkpoint(() -> {
            assertThrows(Exception.class, () -> orderItemDao.createOrUpdate(
                    parentKey,
                    criteria,
                    existing -> {
                        updaterCalled.set(true);
                        return existing;
                    },
                    () -> {
                        generatorCalled.set(true);
                        return SanityOrderItem.builder()
                                .orderId(parentKey).itemName("new").quantity(99).price(99).build();
                    }
            ));
            return null;
        });

        // SELECT FOR UPDATE (1 prepare) → NonUniqueResultException → rollback
        assertTransactionEvents(result, 1, false);
        assertFalse(updaterCalled.get(), "updater must NOT be invoked on NonUniqueResultException");
        assertFalse(generatorCalled.get(), "generator must NOT be invoked on NonUniqueResultException");

        // Both original items must still exist unchanged
        val items = selectItems(parentKey);
        assertEquals(2, items.size(), "Both original items must be unchanged");
    }

    // =========================================================================
    // Helper
    // =========================================================================

    private java.util.List<SanityOrderItem> selectItems(String parentKey) throws Exception {
        val criteria = DetachedCriteria.forClass(SanityOrderItem.class)
                .add(Restrictions.eq("orderId", parentKey));
        return orderItemDao.select(parentKey, criteria, 0, Integer.MAX_VALUE);
    }

    // =========================================================================
    // Skipped tests (require multi-threaded coordination):
    //
    // 17.3 Pessimistic lock prevents concurrent corruption (LookupDao)
    //   - Two threads simultaneously createOrUpdate on same key.
    //   - Uses SELECT FOR UPDATE (PESSIMISTIC_WRITE), so second thread should
    //     block until first commits, then see the first's result.
    //   - Verify no duplicate inserts or lost updates.
    //
    // 17.6 Lock timeout behavior (deadlock sanity)
    //   - Verify the lock is released and transaction rolls back after timeout.
    //   - Use sleep/wait to hold the lock, verify other thread gets timeout error.
    //   - Prevents indefinite locking in deadlock scenarios.
    //
    // 18.3 Pessimistic lock correctness (RelationalDao)
    //   - Same as 17.3 but for relational entities via criteria-based lookup.
    //   - Both CreateOrUpdate variants use getLockedForWrite (SELECT FOR UPDATE),
    //     so concurrent access should be serialized correctly.
    //
    // Note: LockedContext.createOrUpdate() variants are not separately tested
    // here — they delegate to RelationalDao.createOrUpdate(context, ...) which
    // uses CreateOrUpdateInLockedContext opContext (a different opContext that
    // should be tested in the LockAndExecute opContext test class).
    // =========================================================================
}

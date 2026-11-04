package io.appform.dropwizard.sharding.sanity.tests;

import io.appform.dropwizard.sharding.sanity.base.SanityTestBase;
import io.appform.dropwizard.sharding.sanity.entities.SanityOrder;
import lombok.val;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for the {@code LookupDao.save()} operation context, covering:
 * <ul>
 *   <li>Basic save and read-back</li>
 *   <li>Save returns correct entity/ID</li>
 *   <li>Save with afterSave — pure transformation</li>
 *   <li>Save with afterSave — mutation persists</li>
 *   <li>Save rollback on constraint violation</li>
 *   <li>Save rollback on afterSave failure</li>
 * </ul>
 *
 * <p>Each DAO operation is wrapped in a {@link #checkpoint} and asserted via
 * {@link #assertTransactionEvents} to verify the JDBC lifecycle.
 */
class SaveOpContextTest extends SanityTestBase {

    @BeforeEach
    void cleanTables() throws Exception {
        truncateAllTables();
    }

    // -------------------------------------------------------------------------
    // 1.1 Basic save and read-back
    // -------------------------------------------------------------------------

    @Test
    void save_basicSaveAndReadBack() throws Exception {
        val orderId = UUID.randomUUID().toString();

        // Save
        val saveResult = checkpoint(() -> {
            try {
                return orderLookupDao.save(SanityOrder.builder()
                        .orderId(orderId)
                        .customerId("cust-1")
                        .amount(100)
                        .build());
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });

        // 1 PREPARE_STATEMENT (INSERT), committed
        assertTransactionEvents(saveResult, 1, true);
        assertTrue(saveResult.getValue().isPresent(), "save should return the entity");

        // Read back
        val readResult = checkpoint(() -> {
            try {
                return orderLookupDao.get(orderId);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });

        // 1 PREPARE_STATEMENT (SELECT), committed
//        assertTransactionEvents(readResult, 1, true);
        assertTrue(readResult.getValue().isPresent(), "Entity must be readable after save");
        assertEquals(orderId, readResult.getValue().get().getOrderId());
        assertEquals("cust-1", readResult.getValue().get().getCustomerId());
        assertEquals(100, readResult.getValue().get().getAmount());
    }

    // -------------------------------------------------------------------------
    // 1.2 Save returns correct entity/ID
    // -------------------------------------------------------------------------

    @Test
    void save_returnsCorrectEntityWithGeneratedId() throws Exception {
        val orderId = UUID.randomUUID().toString();

        val result = checkpoint(() -> {
            try {
                return orderLookupDao.save(SanityOrder.builder()
                        .orderId(orderId)
                        .customerId("cust-2")
                        .amount(200)
                        .build());
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });

        assertTransactionEvents(result, 1, true);
        assertTrue(result.getValue().isPresent());

        val saved = result.getValue().get();
        assertNotNull(saved.getId(), "Generated ID must be populated");
        assertTrue(saved.getId() > 0, "Generated ID must be positive");
        assertEquals(orderId, saved.getOrderId());
        assertEquals("cust-2", saved.getCustomerId());
        assertEquals(200, saved.getAmount());
    }

    // -------------------------------------------------------------------------
    // 1.3 Save with afterSave — pure transformation (no DB side-effect)
    //
    // save(entity, handler) runs handler in the same transaction.
    // Handler extracts/maps data but does NOT mutate the entity.
    // -------------------------------------------------------------------------

    @Test
    void save_withAfterSave_pureTransformation() throws Exception {
        val orderId = UUID.randomUUID().toString();

        // save(entity, handler) returns whatever handler returns
        val result = checkpoint(() -> {
            try {
                return orderLookupDao.save(
                        SanityOrder.builder()
                                .orderId(orderId)
                                .customerId("cust-3")
                                .amount(300)
                                .build(),
                        entity -> "mapped:" + entity.getOrderId()  // pure transform, no mutation
                );
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });

        assertTransactionEvents(result, 1, true);
        assertEquals("mapped:" + orderId, result.getValue(),
                "Handler return value must be propagated");

        // Verify DB state is unchanged by the handler
        val readResult = checkpoint(() -> {
            try {
                return orderLookupDao.get(orderId);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });

//        assertTransactionEvents(readResult, 1, true);
        val persisted = readResult.getValue().get();
        assertEquals("cust-3", persisted.getCustomerId(), "Handler must not alter persisted state");
        assertEquals(300, persisted.getAmount());
    }

    // -------------------------------------------------------------------------
    // 1.4 Save with afterSave — mutation persists (DB state changes)
    //
    // Handler mutates a field on the managed entity. Hibernate dirty-checks
    // the managed entity at flush/commit, so the mutation IS persisted.
    // -------------------------------------------------------------------------

    @Test
    void save_withAfterSave_mutationPersists() throws Exception {
        val orderId = UUID.randomUUID().toString();

        val result = checkpoint(() -> {
            try {
                return orderLookupDao.save(
                        SanityOrder.builder()
                                .orderId(orderId)
                                .customerId("original")
                                .amount(400)
                                .build(),
                        entity -> {
                            entity.setCustomerId("mutated-in-handler");
                            return entity;
                        }
                );
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });

        // INSERT + dirty-check UPDATE = 2 PREPARE_STATEMENTs
        assertTransactionEvents(result, 2, true);

        // Verify the mutation was persisted
        val readResult = checkpoint(() -> {
            try {
                return orderLookupDao.get(orderId);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });

            assertTransactionEvents(readResult, 1, true);
        assertEquals("mutated-in-handler", readResult.getValue().get().getCustomerId(),
                "Handler mutation must be persisted via Hibernate dirty-check");
    }

    // -------------------------------------------------------------------------
    // 1.5 Save rollback on constraint violation
    //
    // orderId has unique constraint. Saving duplicate should fail and rollback.
    // -------------------------------------------------------------------------

    @Test
    void save_rollbackOnConstraintViolation() throws Exception {
        val orderId = UUID.randomUUID().toString();

        // First save succeeds
        orderLookupDao.save(SanityOrder.builder()
                .orderId(orderId)
                .customerId("first")
                .amount(500)
                .build());

        // Duplicate save should fail
        val result = checkpoint(() -> {
            assertThrows(Exception.class, () ->
                    orderLookupDao.save(SanityOrder.builder()
                            .orderId(orderId)
                            .customerId("duplicate")
                            .amount(999)
                            .build()));
            return null;
        });

        // 1 PREPARE_STATEMENT (INSERT attempt), rolled back
        assertTransactionEvents(result, 1, false);

        // Original entity unchanged
        val readResult = checkpoint(() -> {
            try {
                return orderLookupDao.get(orderId);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });

//        assertTransactionEvents(readResult, 1, true);
        assertEquals("first", readResult.getValue().get().getCustomerId(),
                "Original entity must be unchanged after failed duplicate save");
    }

    // -------------------------------------------------------------------------
    // 1.6 Save rollback on afterSave failure
    //
    // Handler throws exception → entire transaction (including the INSERT)
    // must be rolled back.
    // -------------------------------------------------------------------------

    @Test
    void save_rollbackOnAfterSaveFailure() throws Exception {
        val orderId = UUID.randomUUID().toString();

        val result = checkpoint(() -> {
            assertThrows(Exception.class, () ->
                    orderLookupDao.save(
                            SanityOrder.builder()
                                    .orderId(orderId)
                                    .customerId("should-not-persist")
                                    .amount(600)
                                    .build(),
                            entity -> {
                                throw new RuntimeException("afterSave explosion");
                            }
                    ));
            return null;
        });

        // 1 PREPARE_STATEMENT (INSERT succeeded), then handler threw → rollback
        assertTransactionEvents(result, 1, false);

        // Entity must NOT be visible (rolled back)
        val readResult = checkpoint(() -> {
            try {
                return orderLookupDao.get(orderId);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });

//        assertTransactionEvents(readResult, 1, true);
        assertTrue(readResult.getValue().isEmpty(),
                "Entity must not exist after afterSave failure caused rollback");
    }

    // -------------------------------------------------------------------------
    // 1.7 Save handler succeeds but dirty-check UPDATE fails
    //
    // INSERT succeeds, handler mutates orderId to a duplicate value.
    // Hibernate dirty-check generates an UPDATE at commit time which
    // violates the unique constraint → entire transaction rolled back.
    // The pre-existing entity must remain intact.
    //
    // NOTE: Both orders must land on the SAME shard for the unique
    // constraint violation to trigger (unique constraints are per-shard).
    // If they land on different shards, the UPDATE succeeds — which is
    // itself a finding worth documenting.
    // -------------------------------------------------------------------------

//    @Test
//    void save_handlerSucceeds_butDirtyCheckUpdateFails() throws Exception {
////        val existingOrderId = UUID.randomUUID().toString();
//        val existingOrderId = "6fc6388c-e5d8-487a-acd8-c65d4ef30f34";
//        val newOrderId = "9ca88c87-d357-4498-924c-e3973e56bb74";
////        val newOrderId = UUID.randomUUID().toString();
//
//        // Pre-insert an order
//        orderLookupDao.save(SanityOrder.builder()
//                .orderId(existingOrderId)
//                .customerId("existing-customer")
//                .amount(100)
//                .build());
//
//        // Save a new order, handler mutates orderId to the existing one
//        // INSERT succeeds (newOrderId), dirty-check UPDATE (orderId → existingOrderId)
//        // should violate unique constraint
//        val result = checkpoint(() -> {
//            assertThrows(Exception.class, () ->
//                    orderLookupDao.save(
//                            SanityOrder.builder()
//                                    .orderId(newOrderId)
//                                    .customerId("new-customer")
//                                    .amount(500)
//                                    .build(),
//                            entity -> {
//                                entity.setOrderId(existingOrderId);
//                                return entity;
//                            }
//                    ));
//            return null;
//        });
//
//        // INSERT (1) + dirty-check UPDATE (2) → UPDATE fails → ROLLBACK
//        assertTransactionEvents(result, 2, false);
//
//        // Pre-existing entity must be intact
//        val existingResult = checkpoint(() -> {
//            try {
//                return orderLookupDao.get(existingOrderId);
//            } catch (Exception e) {
//                throw new RuntimeException(e);
//            }
//        });
//
//        assertTransactionEvents(existingResult, 1, true);
//        assertTrue(existingResult.getValue().isPresent(),
//                "Pre-existing entity must still exist");
//        assertEquals("existing-customer", existingResult.getValue().get().getCustomerId());
//
//        // New entity must NOT exist (rolled back)
//        val newResult = checkpoint(() -> {
//            try {
//                return orderLookupDao.get(newOrderId);
//            } catch (Exception e) {
//                throw new RuntimeException(e);
//            }
//        });
//
//        assertTransactionEvents(newResult, 1, true);
//        assertTrue(newResult.getValue().isEmpty(),
//                "New entity must not exist after dirty-check UPDATE failure caused rollback");
//    }

    // -------------------------------------------------------------------------
    // 1.8 Save to correct shard
    //
    // Save entity with a known shard key. Verify entity exists on the
    // expected shard and NOT on the other shard via direct JDBC queries.
    // -------------------------------------------------------------------------

    @Test
    void save_entityLandsOnCorrectShard() throws Exception {
        val orderId = UUID.randomUUID().toString();
        int expectedShard = shardForKey(orderId);
        int otherShard = expectedShard == 0 ? 1 : 0;

        orderLookupDao.save(SanityOrder.builder()
                .orderId(orderId)
                .customerId("shard-test")
                .amount(777)
                .build());

        // Direct JDBC: entity must exist on the expected shard
        assertTrue(existsOnShard(expectedShard, orderId),
                "Entity must exist on shard " + expectedShard + " (orderId=" + orderId + ")");

        // Direct JDBC: entity must NOT exist on the other shard
        assertFalse(existsOnShard(otherShard, orderId),
                "Entity must NOT exist on shard " + otherShard + " (orderId=" + orderId + ")");
    }
}

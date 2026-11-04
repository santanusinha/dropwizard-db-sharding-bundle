package io.appform.dropwizard.sharding.sanity.tests;

import io.appform.dropwizard.sharding.sanity.base.SanityTestBase;
import io.appform.dropwizard.sharding.sanity.entities.SanityOrder;
import lombok.val;
import org.hibernate.Criteria;
import org.hibernate.criterion.Restrictions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.function.Function;
import java.util.function.UnaryOperator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for the {@code GetByLookupKey} opContext.
 *
 * <p>Public methods that use this opContext:
 * <ul>
 *   <li>{@code LookupDao.get(String key)}</li>
 *   <li>{@code LookupDao.get(String key, Function<T, U> handler)}</li>
 *   <li>{@code LookupDao.get(String key, UnaryOperator<Criteria> criteriaUpdater)}</li>
 *   <li>{@code LookupDao.get(String key, UnaryOperator<Criteria> criteriaUpdater, Function<T, U> handler)}</li>
 * </ul>
 *
 * <p>Uses {@link SanityOrder} (parent entity with {@code @LookupKey} on orderId).
 */
class GetByLookupKeyOpContextTest extends SanityTestBase {

    @BeforeEach
    void cleanTables() throws Exception {
        truncateAllTables();
    }

    // =========================================================================
    // get(key)
    // =========================================================================

    @Test
    void get_existingEntity_fieldsMatch() throws Exception {
        val orderId = UUID.randomUUID().toString();

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
        assertTransactionEvents(saveResult, 1, true);

        val getResult = checkpoint(() -> {
            try {
                return orderLookupDao.get(orderId);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        assertTransactionEvents(getResult, 1, true);
        assertTrue(getResult.getValue().isPresent());
        assertEquals(orderId, getResult.getValue().get().getOrderId());
        assertEquals("cust-1", getResult.getValue().get().getCustomerId());
        assertEquals(100, getResult.getValue().get().getAmount());
    }

    @Test
    void get_nonExistentKey_returnsEmpty() throws Exception {

        val orderId = UUID.randomUUID().toString();

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
        assertTransactionEvents(saveResult, 1, true);

        val result = checkpoint(() -> {
            try {
                return orderLookupDao.get("non-existent-key");
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });

        assertTransactionEvents(result, 1, true);
        assertTrue(result.getValue().isEmpty());
    }

    @Test
    void get_afterUpdate_seesLatestValue() throws Exception {
        val orderId = UUID.randomUUID().toString();

        orderLookupDao.save(SanityOrder.builder()
                .orderId(orderId)
                .customerId("original")
                .amount(100)
                .build());

        val res = checkpoint(() -> orderLookupDao.updateInLock(orderId, existing -> {
            val order = existing.orElseThrow();
            order.setCustomerId("updated");
            order.setAmount(999);
            return order;
        }));
        assertTransactionEvents(res, 2, true);

        val result = checkpoint(() -> {
            try {
                return orderLookupDao.get(orderId);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });

        assertTransactionEvents(result, 1, true);
        assertEquals("updated", result.getValue().get().getCustomerId());
        assertEquals(999, result.getValue().get().getAmount());
    }

    @Test
    void get_afterDelete_returnsEmpty() throws Exception {
        val orderId = UUID.randomUUID().toString();

        orderLookupDao.save(SanityOrder.builder()
                .orderId(orderId)
                .customerId("to-delete")
                .amount(100)
                .build());

        val deleteResult = checkpoint(() -> {
            try {
                return orderLookupDao.delete(orderId);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        // DELETE: SELECT FOR UPDATE (1) + DELETE (1) = 2 prepares
        assertTransactionEvents(deleteResult, 2, true);
        assertTrue(deleteResult.getValue());

        val getResult = checkpoint(() -> {
            try {
                return orderLookupDao.get(orderId);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });

        assertTransactionEvents(getResult, 1, true);
        assertTrue(getResult.getValue().isEmpty());
    }

    // =========================================================================
    // get(key, handler)
    // =========================================================================

    @Test
    void getWithHandler_pureTransformation() throws Exception {
        val orderId = UUID.randomUUID().toString();

        orderLookupDao.save(SanityOrder.builder()
                .orderId(orderId)
                .customerId("cust-transform")
                .amount(300)
                .build());

        val result = checkpoint(() -> {
            try {
                return orderLookupDao.get(orderId,
                        (Function<SanityOrder, String>) entity -> "got:" + entity.getCustomerId());
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });

        assertTransactionEvents(result, 1, true);
        assertEquals("got:cust-transform", result.getValue());
    }

    @Test
    void getWithHandler_mutationDoesNotPersist_readOnlySession() throws Exception {
        val orderId = UUID.randomUUID().toString();

        orderLookupDao.save(SanityOrder.builder()
                .orderId(orderId)
                .customerId("original")
                .amount(400)
                .build());

        val result = checkpoint(() -> {
            try {
                return orderLookupDao.get(orderId,
                        (Function<SanityOrder, SanityOrder>) entity -> {
                            entity.setCustomerId("mutated-in-handler");
                            return entity;
                        });
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });

        // Only 1 PREPARE (SELECT). No UPDATE — session is read-only.
        assertTransactionEvents(result, 1, true);

        val readBack = orderLookupDao.get(orderId);
        assertEquals("original", readBack.get().getCustomerId(),
                "Mutation in handler must NOT persist — session is read-only");
    }

    @Test
    void getWithHandler_handlerThrows_rollback() throws Exception {
        val orderId = UUID.randomUUID().toString();

        orderLookupDao.save(SanityOrder.builder()
                .orderId(orderId)
                .customerId("cust-throw")
                .amount(500)
                .build());

        val result = checkpoint(() -> {
            assertThrows(Exception.class, () ->
                    orderLookupDao.get(orderId,
                            (Function<SanityOrder, Object>) entity -> {
                                throw new RuntimeException("handler explosion");
                            }));
            return null;
        });

        assertTransactionEvents(result, 1, false);

        // Entity still exists
        val readBack = orderLookupDao.get(orderId);
        assertTrue(readBack.isPresent());
        assertEquals("cust-throw", readBack.get().getCustomerId());
    }

    // =========================================================================
    // get(key, criteriaUpdater)
    // =========================================================================

    @Test
    void getWithCriteriaUpdater_matchingFilter_returnsEntity() throws Exception {
        val orderId = UUID.randomUUID().toString();

        orderLookupDao.save(SanityOrder.builder()
                .orderId(orderId)
                .customerId("target-customer")
                .amount(500)
                .build());

        val result = checkpoint(() -> {
            try {
                return orderLookupDao.get(orderId,
                        (UnaryOperator<Criteria>) criteria -> criteria.add(
                                Restrictions.eq("customerId", "target-customer")));
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });

        assertTransactionEvents(result, 1, true);
        assertTrue(result.getValue().isPresent());
        assertEquals("target-customer", result.getValue().get().getCustomerId());
    }

    @Test
    void getWithCriteriaUpdater_nonMatchingFilter_returnsEmpty() throws Exception {
        val orderId = UUID.randomUUID().toString();

        orderLookupDao.save(SanityOrder.builder()
                .orderId(orderId)
                .customerId("real-customer")
                .amount(500)
                .build());

        val result = checkpoint(() -> {
            try {
                return orderLookupDao.get(orderId,
                        (UnaryOperator<Criteria>) criteria -> criteria.add(
                                Restrictions.eq("customerId", "wrong-customer")));
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });

        assertTransactionEvents(result, 1, true);
        assertTrue(result.getValue().isEmpty());
    }

    // =========================================================================
    // get(key, criteriaUpdater, handler)
    // =========================================================================

    @Test
    void getWithCriteriaUpdaterAndHandler_matchingFilter_transformApplied() throws Exception {
        val orderId = UUID.randomUUID().toString();

        orderLookupDao.save(SanityOrder.builder()
                .orderId(orderId)
                .customerId("cust-combo")
                .amount(700)
                .build());

        val result = checkpoint(() -> {
            try {
                return orderLookupDao.get(orderId,
                        (UnaryOperator<Criteria>) criteria -> criteria.add(
                                Restrictions.eq("customerId", "cust-combo")),
                        entity -> "combo:" + entity.getAmount());
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });

        assertTransactionEvents(result, 1, true);
        assertEquals("combo:700", result.getValue());
    }

    @Test
    void getWithCriteriaUpdaterAndHandler_nonMatchingFilter_handlerReceivesNull() throws Exception {
        val orderId = UUID.randomUUID().toString();

        orderLookupDao.save(SanityOrder.builder()
                .orderId(orderId)
                .customerId("exists")
                .amount(800)
                .build());

        // criteriaUpdater filters it out → entity is null → handler receives null
        val result = checkpoint(() -> {
            try {
                return orderLookupDao.get(orderId,
                        (UnaryOperator<Criteria>) criteria -> criteria.add(
                                Restrictions.eq("customerId", "does-not-exist")),
                        entity -> entity == null ? "null-entity" : "found");
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });

        assertTransactionEvents(result, 1, true);
        assertEquals("null-entity", result.getValue(),
                "Handler must receive null when criteriaUpdater filters out the entity");
    }

    @Test
    void getWithCriteriaUpdaterAndHandler_handlerMutates_doesNotPersist() throws Exception {
        val orderId = UUID.randomUUID().toString();

        orderLookupDao.save(SanityOrder.builder()
                .orderId(orderId)
                .customerId("immutable")
                .amount(900)
                .build());

        val result = checkpoint(() -> {
            try {
                return orderLookupDao.get(orderId,
                        (UnaryOperator<Criteria>) criteria -> criteria,  // no extra filter
                        entity -> {
                            entity.setCustomerId("mutated");
                            return entity;
                        });
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });

        assertTransactionEvents(result, 1, true);

        val readBack = orderLookupDao.get(orderId);
        assertEquals("immutable", readBack.get().getCustomerId(),
                "Mutation via handler must NOT persist — session is read-only");
    }
}

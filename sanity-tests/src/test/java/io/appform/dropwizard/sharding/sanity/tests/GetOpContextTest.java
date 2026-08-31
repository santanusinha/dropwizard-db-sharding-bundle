package io.appform.dropwizard.sharding.sanity.tests;

import io.appform.dropwizard.sharding.sanity.base.SanityTestBase;
import io.appform.dropwizard.sharding.sanity.entities.SanityOrder;
import io.appform.dropwizard.sharding.sanity.entities.SanityOrderItem;
import lombok.val;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for the {@code Get} opContext (RelationalDao).
 *
 * <p>Public methods that use this opContext:
 * <ul>
 *   <li>{@code RelationalDao.get(String parentKey, Object key)}</li>
 *   <li>{@code RelationalDao.get(String parentKey, Object key, Function<T, U> handler)}</li>
 * </ul>
 *
 * <p>Uses {@link SanityOrderItem} with parentKey-based shard routing.
 * Gets by {@code @Id} (the auto-generated Long id), NOT by orderId.
 */
class GetOpContextTest extends SanityTestBase {

    @BeforeEach
    void cleanTables() throws Exception {
        truncateAllTables();
    }

    // =========================================================================
    // get(parentKey, id)
    // =========================================================================

    @Test
    void get_existingEntity_fieldsMatch() throws Exception {
        val parentKey = UUID.randomUUID().toString();

        val saved = orderItemDao.save(parentKey, SanityOrderItem.builder()
                .orderId(parentKey)
                .itemName("widget")
                .quantity(5)
                .price(200)
                .build());
        assertNotNull(saved.orElse(null));
        val generatedId = saved.get().getId();

        val result = checkpoint(() -> {
            try {
                return orderItemDao.get(parentKey, generatedId);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });

        assertTransactionEvents(result, 1, true);
        assertTrue(result.getValue().isPresent());
        assertEquals(parentKey, result.getValue().get().getOrderId());
        assertEquals("widget", result.getValue().get().getItemName());
        assertEquals(5, result.getValue().get().getQuantity());
        assertEquals(200, result.getValue().get().getPrice());
    }

    @Test
    void get_nonExistentId_returnsEmpty() throws Exception {
        val parentKey = UUID.randomUUID().toString();

        val result = checkpoint(() -> {
            try {
                return orderItemDao.get(parentKey, 999999L);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });

        assertTransactionEvents(result, 1, true);
        assertTrue(result.getValue().isEmpty());
    }

    @Test
    void get_afterUpdate_seesLatestValue() throws Exception {
        val parentKey = UUID.randomUUID().toString();

        val saved = orderItemDao.save(parentKey, SanityOrderItem.builder()
                .orderId(parentKey)
                .itemName("bolt")
                .quantity(10)
                .price(50)
                .build());
        val generatedId = saved.get().getId();

        // Update quantity
        orderItemDao.update(parentKey, generatedId, existing -> {
            existing.setQuantity(99);
            return existing;
        });

        val result = checkpoint(() -> {
            try {
                return orderItemDao.get(parentKey, generatedId);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });

        assertTransactionEvents(result, 1, true);
        assertEquals(99, result.getValue().get().getQuantity(),
                "Must see updated value, not stale");
    }

    // =========================================================================
    // get(parentKey, id, handler)
    // =========================================================================

    @Test
    void getWithHandler_pureTransformation() throws Exception {
        val parentKey = UUID.randomUUID().toString();

        val saved = orderItemDao.save(parentKey, SanityOrderItem.builder()
                .orderId(parentKey)
                .itemName("gadget")
                .quantity(7)
                .price(350)
                .build());
        val generatedId = saved.get().getId();

        val result = checkpoint(() -> {
            try {
                return orderItemDao.get(parentKey, generatedId,
                        entity -> "item:" + entity.getItemName());
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });

        assertTransactionEvents(result, 1, true);
        assertEquals("item:gadget", result.getValue());
    }

    @Test
    void getWithHandler_mutationDoesNotPersist_readOnlySession() throws Exception {
        val parentKey = UUID.randomUUID().toString();

        val saved = orderItemDao.save(parentKey, SanityOrderItem.builder()
                .orderId(parentKey)
                .itemName("screw")
                .quantity(20)
                .price(5)
                .build());
        val generatedId = saved.get().getId();

        val result = checkpoint(() -> {
            try {
                return orderItemDao.get(parentKey, generatedId, entity -> {
                    entity.setQuantity(999);
                    return entity;
                });
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });

        // Only 1 PREPARE (SELECT). No UPDATE — session is read-only.
        assertTransactionEvents(result, 1, true);

        val readBack = orderItemDao.get(parentKey, generatedId);
        assertEquals(20, readBack.get().getQuantity(),
                "Mutation in handler must NOT persist — session is read-only");
    }

    @Test
    void getWithHandler_handlerThrows_rollback() throws Exception {
        val parentKey = UUID.randomUUID().toString();

        val saved = orderItemDao.save(parentKey, SanityOrderItem.builder()
                .orderId(parentKey)
                .itemName("nut")
                .quantity(15)
                .price(3)
                .build());
        val generatedId = saved.get().getId();

        val result = checkpoint(() -> {
            assertThrows(Exception.class, () ->
                    orderItemDao.get(parentKey, generatedId, entity -> {
                        throw new RuntimeException("handler explosion");
                    }));
            return null;
        });

        assertTransactionEvents(result, 1, false);

        // Entity still exists
        val readBack = orderItemDao.get(parentKey, generatedId);
        assertTrue(readBack.isPresent());
        assertEquals("nut", readBack.get().getItemName());
    }

    @Test
    void getWithHandler_nonExistentId_handlerReceivesNull() throws Exception {
        val parentKey = UUID.randomUUID().toString();

        val result = checkpoint(() -> {
            try {
                return orderItemDao.get(parentKey, 999999L,
                        entity -> entity == null ? "null-entity" : "found");
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });

        assertTransactionEvents(result, 1, true);
        assertEquals("null-entity", result.getValue(),
                "Handler must receive null when entity doesn't exist");
    }

    // =========================================================================
    // get(List<String> keys) — batch get across shards
    //
    // Internally uses Get opContext (not GetByLookupKey). Groups keys by shard,
    // runs one query per shard with Restrictions.in(...), merges results.
    // =========================================================================

    @Test
    void getBatch_multipleKeys_returnsAll() throws Exception {
        val orderId1 = UUID.randomUUID().toString();
        val orderId2 = UUID.randomUUID().toString();
        val orderId3 = UUID.randomUUID().toString();

        orderLookupDao.save(SanityOrder.builder().orderId(orderId1).customerId("c1").amount(100).build());
        orderLookupDao.save(SanityOrder.builder().orderId(orderId2).customerId("c2").amount(200).build());
        orderLookupDao.save(SanityOrder.builder().orderId(orderId3).customerId("c3").amount(300).build());

        int uniqueShards = uniqueShardCount(orderLookupDao.getShardCalculator(), java.util.List.of(orderId1, orderId2, orderId3));

        val result = checkpoint(() -> orderLookupDao.get(java.util.List.of(orderId1, orderId2, orderId3)));

        assertEquals(3, result.getValue().size(), "Batch get must return all 3 entities");
        assertTrue(result.getValue().stream().anyMatch(o -> orderId1.equals(o.getOrderId())));
        assertTrue(result.getValue().stream().anyMatch(o -> orderId2.equals(o.getOrderId())));
        assertTrue(result.getValue().stream().anyMatch(o -> orderId3.equals(o.getOrderId())));
        assertScatterGatherEvents(result, uniqueShards, 1, true);
    }

    @Test
    void getBatch_someKeysNonExistent_returnsOnlyExisting() throws Exception {
        val orderId1 = UUID.randomUUID().toString();
        val orderId2 = UUID.randomUUID().toString();
        val nonExistent = UUID.randomUUID().toString();

        int uniqueShards = uniqueShardCount(orderLookupDao.getShardCalculator(), java.util.List.of(orderId1, nonExistent, orderId2));

        orderLookupDao.save(SanityOrder.builder().orderId(orderId1).customerId("c1").amount(100).build());
        orderLookupDao.save(SanityOrder.builder().orderId(orderId2).customerId("c2").amount(200).build());

        val result = checkpoint(() -> orderLookupDao.get(java.util.List.of(orderId1, nonExistent, orderId2)));

        assertEquals(2, result.getValue().size(), "Batch get must return only existing entities");
        assertScatterGatherEvents(result, uniqueShards, 1, true);
        assertTrue(result.getValue().stream().noneMatch(o -> nonExistent.equals(o.getOrderId())));
    }

    @Test
    void getBatch_emptyList_returnsEmpty() throws Exception {
        val result = orderLookupDao.get(java.util.List.of());
        assertTrue(result.isEmpty(), "Batch get on empty list must return empty");
    }
}

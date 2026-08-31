package io.appform.dropwizard.sharding.sanity.tests;

import io.appform.dropwizard.sharding.sanity.base.SanityTestBase;
import io.appform.dropwizard.sharding.sanity.entities.SanityOrderItem;
import lombok.val;
import org.hibernate.criterion.DetachedCriteria;
import org.hibernate.criterion.Restrictions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@code RelationalDao.save()} operation context.
 *
 * <p>Key difference from LookupDao: shard routing is via an explicit {@code parentKey}
 * parameter, not a {@code @LookupKey} field on the entity.
 *
 * <p>Uses {@link SanityOrderItem} with {@code orderId} as the parent key.
 * Unique constraint on {@code (order_id, item_name)}.
 */
class RelationalSaveOpContextTest extends SanityTestBase {

    @BeforeEach
    void cleanTables() throws Exception {
        truncateAllTables();
    }

    // -------------------------------------------------------------------------
    // 1.1 Basic save and read-back
    // -------------------------------------------------------------------------

    @Test
    void save_basicSaveAndReadBack() throws Exception {
        val parentKey = UUID.randomUUID().toString();

        val saveResult = checkpoint(() -> {
            try {
                return orderItemDao.save(parentKey, SanityOrderItem.builder()
                        .orderId(parentKey)
                        .itemName("widget")
                        .quantity(3)
                        .price(250)
                        .build());
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });

        assertTransactionEvents(saveResult, 1, true);
        assertTrue(saveResult.getValue().isPresent(), "save should return the entity");

        // Read back via select
        val readResult = checkpoint(() -> {
            try {
                return selectItemsByParentKey(parentKey);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });

//        assertTransactionEvents(readResult, 1, true);
        assertEquals(1, readResult.getValue().size());
        val item = readResult.getValue().get(0);
        assertEquals(parentKey, item.getOrderId());
        assertEquals("widget", item.getItemName());
        assertEquals(3, item.getQuantity());
        assertEquals(250, item.getPrice());
    }

    // -------------------------------------------------------------------------
    // 1.2 Save returns correct entity with generated ID
    // -------------------------------------------------------------------------

    @Test
    void save_returnsCorrectEntityWithGeneratedId() throws Exception {
        val parentKey = UUID.randomUUID().toString();

        val result = checkpoint(() -> {
            try {
                return orderItemDao.save(parentKey, SanityOrderItem.builder()
                        .orderId(parentKey)
                        .itemName("gadget")
                        .quantity(1)
                        .price(500)
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
        assertEquals(parentKey, saved.getOrderId());
        assertEquals("gadget", saved.getItemName());
        assertEquals(1, saved.getQuantity());
        assertEquals(500, saved.getPrice());
    }

    // -------------------------------------------------------------------------
    // 1.3 Save with afterSave — pure transformation (no DB side-effect)
    // -------------------------------------------------------------------------

    @Test
    void save_withAfterSave_pureTransformation() throws Exception {
        val parentKey = UUID.randomUUID().toString();

        val result = checkpoint(() -> {
            try {
                return orderItemDao.save(parentKey,
                        SanityOrderItem.builder()
                                .orderId(parentKey)
                                .itemName("bolt")
                                .quantity(10)
                                .price(5)
                                .build(),
                        entity -> "id:" + entity.getId()
                );
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });

        assertTransactionEvents(result, 1, true);
        assertTrue(result.getValue().toString().startsWith("id:"),
                "Handler return value must be propagated");

        // Verify DB state is unchanged
        val items = selectItemsByParentKey(parentKey);
        assertEquals(1, items.size());
        assertEquals("bolt", items.get(0).getItemName());
        assertEquals(10, items.get(0).getQuantity());
    }

    // -------------------------------------------------------------------------
    // 1.4 Save with afterSave — mutation persists
    // -------------------------------------------------------------------------

    @Test
    void save_withAfterSave_mutationPersists() throws Exception {
        val parentKey = UUID.randomUUID().toString();

        val result = checkpoint(() -> {
            try {
                return orderItemDao.save(parentKey,
                        SanityOrderItem.builder()
                                .orderId(parentKey)
                                .itemName("nut")
                                .quantity(5)
                                .price(3)
                                .build(),
                        entity -> {
                            entity.setQuantity(99);
                            return entity;
                        }
                );
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });

        // INSERT + dirty-check UPDATE = 2 PREPARE_STATEMENTs
        assertTransactionEvents(result, 2, true);

        // Verify mutation was persisted
        val items = selectItemsByParentKey(parentKey);
        assertEquals(1, items.size());
        assertEquals(99, items.get(0).getQuantity(),
                "Handler mutation must be persisted via Hibernate dirty-check");
    }

    // -------------------------------------------------------------------------
    // 1.5 Save rollback on constraint violation
    //
    // Unique constraint on (order_id, item_name). Duplicate should fail.
    // -------------------------------------------------------------------------

    @Test
    void save_rollbackOnConstraintViolation() throws Exception {
        val parentKey = UUID.randomUUID().toString();

        // First save succeeds
        orderItemDao.save(parentKey, SanityOrderItem.builder()
                .orderId(parentKey)
                .itemName("washer")
                .quantity(10)
                .price(2)
                .build());

        // Duplicate (same order_id + item_name) should fail
        val result = checkpoint(() -> {
            assertThrows(Exception.class, () ->
                    orderItemDao.save(parentKey, SanityOrderItem.builder()
                            .orderId(parentKey)
                            .itemName("washer")
                            .quantity(99)
                            .price(99)
                            .build()));
            return null;
        });

        // 1 PREPARE_STATEMENT (INSERT attempt), rolled back
        assertTransactionEvents(result, 1, false);

        // Only original item exists
        val items = selectItemsByParentKey(parentKey);
        assertEquals(1, items.size());
        assertEquals(10, items.get(0).getQuantity(),
                "Original entity must be unchanged after failed duplicate save");
    }

    // -------------------------------------------------------------------------
    // 1.6 Save rollback on afterSave failure
    // -------------------------------------------------------------------------

    @Test
    void save_rollbackOnAfterSaveFailure() throws Exception {
        val parentKey = UUID.randomUUID().toString();

        val result = checkpoint(() -> {
            assertThrows(Exception.class, () ->
                    orderItemDao.save(parentKey,
                            SanityOrderItem.builder()
                                    .orderId(parentKey)
                                    .itemName("screw")
                                    .quantity(20)
                                    .price(1)
                                    .build(),
                            entity -> {
                                throw new RuntimeException("afterSave explosion");
                            }
                    ));
            return null;
        });

        // 1 PREPARE_STATEMENT (INSERT succeeded), handler threw → rollback
        assertTransactionEvents(result, 1, false);

        // Entity must NOT exist (rolled back)
        val items = selectItemsByParentKey(parentKey);
        assertTrue(items.isEmpty(),
                "Entity must not exist after afterSave failure caused rollback");
    }

    // -------------------------------------------------------------------------
    // 1.7 Save to correct shard
    // -------------------------------------------------------------------------

    @Test
    void save_entityLandsOnCorrectShard() throws Exception {
        val parentKey = UUID.randomUUID().toString();
        int expectedShard = shardForKey(parentKey);
        int otherShard = expectedShard == 0 ? 1 : 0;

        orderItemDao.save(parentKey, SanityOrderItem.builder()
                .orderId(parentKey)
                .itemName("shard-test-item")
                .quantity(1)
                .price(1)
                .build());

        // Direct JDBC: entity must exist on the expected shard
        assertTrue(existsOnShardItems(expectedShard, parentKey, "shard-test-item"),
                "Entity must exist on shard " + expectedShard);

        // Direct JDBC: entity must NOT exist on the other shard
        assertFalse(existsOnShardItems(otherShard, parentKey, "shard-test-item"),
                "Entity must NOT exist on shard " + otherShard);
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private List<SanityOrderItem> selectItemsByParentKey(String parentKey) throws Exception {
        val criteria = DetachedCriteria.forClass(SanityOrderItem.class)
                .add(Restrictions.eq("orderId", parentKey));
        return orderItemDao.select(parentKey, criteria, 0, Integer.MAX_VALUE);
    }
}

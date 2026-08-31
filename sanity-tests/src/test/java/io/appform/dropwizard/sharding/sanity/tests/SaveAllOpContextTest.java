package io.appform.dropwizard.sharding.sanity.tests;

import io.appform.dropwizard.sharding.sanity.base.SanityTestBase;
import io.appform.dropwizard.sharding.sanity.entities.SanityOrderItem;
import lombok.val;
import org.hibernate.criterion.DetachedCriteria;
import org.hibernate.criterion.Restrictions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@code RelationalDao.saveAll()} operation context.
 *
 * <p>{@code saveAll(parentKey, entities)} saves a batch of entities in a single
 * transaction on the shard determined by {@code parentKey}.
 *
 * <p>Uses {@link SanityOrderItem} with unique constraint on {@code (order_id, item_name)}.
 */
class SaveAllOpContextTest extends SanityTestBase {

    @BeforeEach
    void cleanTables() throws Exception {
        truncateAllTables();
    }

    // -------------------------------------------------------------------------
    // 2.1 Batch save and read-back
    // -------------------------------------------------------------------------

    @Test
    void saveAll_batchSaveAndReadBack() throws Exception {
        val parentKey = UUID.randomUUID().toString();
        val items = buildItems(parentKey, 5);

        val result = checkpoint(() -> {
            try {
                return orderItemDao.saveAll(parentKey, items);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });

        // 5 PREPARE_STATEMENTs (one INSERT per entity), committed
        assertTransactionEvents(result, 5, true);
        assertTrue(result.getValue(), "saveAll should return true on success");

        val saved = selectItems(parentKey);
        assertEquals(5, saved.size(), "All 5 items must be readable");

        for (int i = 1; i <= 5; i++) {
            val name = "item-" + i;
            val match = saved.stream().filter(it -> name.equals(it.getItemName())).findFirst();
            assertTrue(match.isPresent(), "Item '" + name + "' must be present");
            assertEquals(i, match.get().getQuantity());
            assertEquals(i * 100, match.get().getPrice());
        }
    }

    // -------------------------------------------------------------------------
    // 2.2 Atomicity — failure mid-batch rolls back ALL
    //
    // Pre-insert item-3. Then saveAll 5 items where item-3 hits a unique
    // constraint violation. All 5 must be rolled back; only the
    // pre-inserted item-3 should remain.
    // -------------------------------------------------------------------------

    @Test
    void saveAll_constraintViolationMidBatch_rollsBackAll() throws Exception {
        val parentKey = UUID.randomUUID().toString();

        // Pre-insert item-3
        orderItemDao.save(parentKey, SanityOrderItem.builder()
                .orderId(parentKey)
                .itemName("item-3")
                .quantity(99)
                .price(9999)
                .build());

        val items = buildItems(parentKey, 5);

        // saveAll should fail at item-3 (duplicate)
        val result = checkpoint(() -> {
            assertThrows(Exception.class, () -> orderItemDao.saveAll(parentKey, items));
            return null;
        });

        // Items 1, 2 insert OK (2 prepares), item-3 hits violation (1 prepare) → rollback
        assertTransactionEvents(result, 3, false);

        // Only the pre-inserted item-3 should remain
        val afterItems = selectItems(parentKey);
        assertEquals(1, afterItems.size(),
                "Only the pre-inserted item-3 should remain; batch must be fully rolled back");
        assertEquals("item-3", afterItems.get(0).getItemName());
        assertEquals(99, afterItems.get(0).getQuantity(),
                "Pre-inserted item-3 must retain its original data");
    }

    // -------------------------------------------------------------------------
    // 2.3 Empty batch
    // -------------------------------------------------------------------------

    @Test
    void saveAll_emptyBatch_noErrorNoSideEffects() throws Exception {
        val parentKey = UUID.randomUUID().toString();

        // Pre-insert one item to verify saveAll doesn't accidentally affect it
        orderItemDao.save(parentKey, SanityOrderItem.builder()
                .orderId(parentKey)
                .itemName("existing")
                .quantity(1)
                .price(1)
                .build());

        val result = checkpoint(() -> {
            try {
                return orderItemDao.saveAll(parentKey, Collections.emptyList());
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });

        // Empty batch: 0 PREPARE_STATEMENTs, committed
        assertTransactionEvents(result, 0, true);
        assertTrue(result.getValue(), "saveAll on empty batch should return true");

        // Pre-existing item unaffected
        val items = selectItems(parentKey);
        assertEquals(1, items.size());
        assertEquals("existing", items.get(0).getItemName());
    }

    // -------------------------------------------------------------------------
    // 2.4 Large batch save
    // -------------------------------------------------------------------------

    @Test
    void saveAll_largeBatch_allReadableWithCorrectData() throws Exception {
        val parentKey = UUID.randomUUID().toString();
        int batchSize = 100;

        val items = IntStream.rangeClosed(1, batchSize)
                .mapToObj(i -> SanityOrderItem.builder()
                        .orderId(parentKey)
                        .itemName("large-item-" + i)
                        .quantity(i)
                        .price(i * 10)
                        .build())
                .collect(Collectors.toList());

        val result = checkpoint(() -> {
            try {
                return orderItemDao.saveAll(parentKey, items);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });

        // 100 PREPARE_STATEMENTs, committed
        assertTransactionEvents(result, batchSize, true);
        assertTrue(result.getValue());

        val saved = selectItems(parentKey);
        assertEquals(batchSize, saved.size(), "All " + batchSize + " items must be readable");

        // Verify no data corruption — spot-check first, middle, last
        for (int i : List.of(1, 50, 100)) {
            val name = "large-item-" + i;
            val match = saved.stream().filter(it -> name.equals(it.getItemName())).findFirst();
            assertTrue(match.isPresent(), "Item '" + name + "' must be present");
            assertEquals(i, match.get().getQuantity());
            assertEquals(i * 10, match.get().getPrice());
        }
    }

    // -------------------------------------------------------------------------
    // 2.5 SaveAll on correct shard
    // -------------------------------------------------------------------------

    @Test
    void saveAll_allEntitiesLandOnCorrectShard() throws Exception {
        val parentKey = UUID.randomUUID().toString();
        int expectedShard = shardForKey(parentKey);
        int otherShard = expectedShard == 0 ? 1 : 0;

        val items = buildItems(parentKey, 3);
        orderItemDao.saveAll(parentKey, items);

        // All 3 must exist on the expected shard
        for (int i = 1; i <= 3; i++) {
            assertTrue(existsOnShardItems(expectedShard, parentKey, "item-" + i),
                    "item-" + i + " must exist on shard " + expectedShard);
            assertFalse(existsOnShardItems(otherShard, parentKey, "item-" + i),
                    "item-" + i + " must NOT exist on shard " + otherShard);
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private List<SanityOrderItem> buildItems(String parentKey, int count) {
        return IntStream.rangeClosed(1, count)
                .mapToObj(i -> SanityOrderItem.builder()
                        .orderId(parentKey)
                        .itemName("item-" + i)
                        .quantity(i)
                        .price(i * 100)
                        .build())
                .collect(Collectors.toList());
    }

    private List<SanityOrderItem> selectItems(String parentKey) throws Exception {
        val criteria = DetachedCriteria.forClass(SanityOrderItem.class)
                .add(Restrictions.eq("orderId", parentKey));
        return orderItemDao.select(parentKey, criteria, 0, Integer.MAX_VALUE);
    }
}

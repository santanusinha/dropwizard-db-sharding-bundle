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
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sanity tests for DAO operations and JDBC/transaction lifecycle tracing.
 */
class DummySanityTest extends SanityTestBase {

    @BeforeEach
    void cleanTables() throws Exception {
        truncateAllTables();
    }

    // -------------------------------------------------------------------------
    // TC-1: SaveAll (5 items) + read all back
    // -------------------------------------------------------------------------

    @Test
    void saveAll_fiveItems_allVisibleAfterSave() throws Exception {
        val orderId = UUID.randomUUID().toString();

        val items = IntStream.rangeClosed(1, 5)
                .mapToObj(i -> SanityOrderItem.builder()
                        .orderId(orderId)
                        .itemName("item-" + i)
                        .quantity(i)
                        .price(i * 100)
                        .build())
                .collect(Collectors.toList());

        boolean saved = orderItemDao.saveAll(orderId, items);
        assertTrue(saved, "saveAll should return true on success");

        val afterItems = selectItemsByOrderId(orderId);
        assertEquals(5, afterItems.size(), "All 5 items must be visible after saveAll");
    }

    // -------------------------------------------------------------------------
    // TC-2: SaveAll with exception at 3rd item -> full rollback
    // -------------------------------------------------------------------------

    @Test
    void saveAll_exceptionAtThirdItem_allRolledBack() throws Exception {
        val orderId = UUID.randomUUID().toString();

        // Pre-insert item-3 so the batch will hit a unique constraint violation
        orderItemDao.save(orderId, SanityOrderItem.builder()
                .orderId(orderId)
                .itemName("item-3")
                .quantity(99)
                .price(9999)
                .build());

        val items = IntStream.rangeClosed(1, 5)
                .mapToObj(i -> SanityOrderItem.builder()
                        .orderId(orderId)
                        .itemName("item-" + i)
                        .quantity(i)
                        .price(i * 100)
                        .build())
                .collect(Collectors.toList());

        // Checkpoint: saveAll should fail and rollback
        // Items 1, 2 insert OK (2 prepares), item-3 hits constraint violation (1 prepare) → rollback
        val result = checkpoint(() -> {
            assertThrows(Exception.class, () -> orderItemDao.saveAll(orderId, items));
            return null;
        });

        assertTransactionEvents(result, 3, false);

        // Only the pre-inserted item-3 should remain
        val afterItems = selectItemsByOrderId(orderId);
        assertEquals(1, afterItems.size(),
                "Only the pre-inserted item-3 should remain; batch must be fully rolled back");
    }

    // -------------------------------------------------------------------------
    // TC-3: Save and read-back via LookupDao
    // -------------------------------------------------------------------------

    @Test
    void saveOrderAndReadBack() throws Exception {
        val orderId = UUID.randomUUID().toString();

        val order = SanityOrder.builder()
                .orderId(orderId)
                .customerId("customer-42")
                .amount(500)
                .build();
        val saved = orderLookupDao.save(order);
        assertTrue(saved.isPresent(), "save should succeed");

        val after = orderLookupDao.get(orderId);
        assertTrue(after.isPresent(), "Order must be visible after save");
        assertEquals(orderId, after.get().getOrderId());
        assertEquals("customer-42", after.get().getCustomerId());
        assertEquals(500, after.get().getAmount());
    }

    // -------------------------------------------------------------------------
    // TC-4: Lifecycle tracing — single read
    //
    // Expected: [BEGIN_TRANSACTION, GET_CONNECTION, PREPARE_STATEMENT, COMMIT, RELEASE_CONNECTION]
    // -------------------------------------------------------------------------

    @Test
    void lifecycleTracing_singleRead() throws Exception {
        val orderId = UUID.randomUUID().toString();

        val result = checkpoint(() -> {
            try {
                return orderLookupDao.get(orderId);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });

        // 1 PREPARE_STATEMENT (the SELECT query), committed
        assertTransactionEvents(result, 1, true);
        assertTrue(result.getValue().isEmpty(), "Order should not exist");
    }

    // -------------------------------------------------------------------------
    // TC-5: LockedContext — save parent + 5 children in one transaction
    //
    // Expected: [BEGIN_TRANSACTION, GET_CONNECTION, PREPARE_STATEMENT × 6, COMMIT, RELEASE_CONNECTION]
    //   (1 insert for parent + 2 individual saves + 3 batch saves = 6 prepares)
    // -------------------------------------------------------------------------

    @Test
    void lockedContext_saveParentAndChildrenInSingleTransaction() throws Exception {
        val orderId = UUID.randomUUID().toString();

        val order = SanityOrder.builder()
                .orderId(orderId)
                .customerId("customer-99")
                .amount(1000)
                .build();

        val item1 = SanityOrderItem.builder()
                .orderId(orderId).itemName("widget").quantity(2).price(300).build();
        val item2 = SanityOrderItem.builder()
                .orderId(orderId).itemName("gadget").quantity(1).price(400).build();
        val batchItems = List.of(
                SanityOrderItem.builder()
                        .orderId(orderId).itemName("bolt").quantity(10).price(10).build(),
                SanityOrderItem.builder()
                        .orderId(orderId).itemName("nut").quantity(10).price(5).build(),
                SanityOrderItem.builder()
                        .orderId(orderId).itemName("washer").quantity(20).price(2).build()
        );

        val result = checkpoint(() ->
                orderLookupDao.saveAndGetExecutor(order)
                        .save(orderItemDao, parent -> item1)
                        .save(orderItemDao, parent -> item2)
                        .saveAll(orderItemDao, parent -> batchItems)
                        .execute()
        );

        // 6 PREPARE_STATEMENTs (1 parent + 5 children), committed
        assertTransactionEvents(result, 6, true);

        // Functional assertions
        assertNotNull(result.getValue());
        assertEquals(orderId, result.getValue().getOrderId());
        assertEquals(5, selectItemsByOrderId(orderId).size());
    }

    // -------------------------------------------------------------------------
    // Helper
    // -------------------------------------------------------------------------

    private List<SanityOrderItem> selectItemsByOrderId(String orderId) throws Exception {
        val criteria = DetachedCriteria.forClass(SanityOrderItem.class)
                .add(Restrictions.eq("orderId", orderId));
        return orderItemDao.select(orderId, criteria, 0, Integer.MAX_VALUE);
    }
}

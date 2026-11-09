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
 * Tests for the {@code LockAndExecute} opContext.
 *
 * <p>Two modes:
 * <ul>
 *   <li><b>INSERT</b> ({@code saveAndGetExecutor(entity)}): persists parent, then
 *       drains queued operations against the saved entity. All in one transaction.</li>
 *   <li><b>READ</b> ({@code lockAndGetExecutor(id)}): SELECT FOR UPDATE on parent,
 *       then drains queued operations. All in one transaction. Throws if entity not found.</li>
 * </ul>
 *
 * <p>Chainable operations: {@code .save()}, {@code .saveAll()}, {@code .mutate()},
 * {@code .apply()}, {@code .update()}, {@code .filter()}, {@code .createOrUpdate()}.
 * These queue {@code Consumer<T>} entries — nothing executes until {@code .execute()}.
 *
 * <p>Public methods using this opContext:
 * <ul>
 *   <li>{@code LookupDao.saveAndGetExecutor(entity)} — INSERT mode</li>
 *   <li>{@code LookupDao.lockAndGetExecutor(id)} — READ mode</li>
 *   <li>{@code RelationalDao.saveAndGetExecutor(parentKey, entity)} — INSERT mode</li>
 *   <li>{@code RelationalDao.lockAndGetExecutor(parentKey, criteria)} — READ mode</li>
 * </ul>
 */
class LockAndExecuteOpContextTest extends SanityTestBase {

    @BeforeEach
    void cleanTables() throws Exception {
        truncateAllTables();
    }

    // =========================================================================
    // 20.1 INSERT mode — save parent + execute multiple operation types
    // =========================================================================

    @Test
    void insertMode_saveParentAndExecuteMultipleOps() throws Exception {
        val orderId = UUID.randomUUID().toString();

        val order = SanityOrder.builder()
                .orderId(orderId).customerId("customer-1").amount(100).build();

        val item1 = SanityOrderItem.builder()
                .orderId(orderId).itemName("widget").quantity(2).price(300).build();
        val item2 = SanityOrderItem.builder()
                .orderId(orderId).itemName("gadget").quantity(1).price(400).build();
        val batchItems = List.of(
                SanityOrderItem.builder().orderId(orderId).itemName("bolt").quantity(10).price(10).build(),
                SanityOrderItem.builder().orderId(orderId).itemName("nut").quantity(10).price(5).build()
        );

        val result = checkpoint(() ->
                orderLookupDao.saveAndGetExecutor(order)
                        .mutate(parent -> parent.setAmount(999))             // mutate parent
                        .save(orderItemDao, parent -> item1)                 // save child 1
                        .save(orderItemDao, parent -> item2)                 // save child 2
                        .saveAll(orderItemDao, parent -> batchItems)         // save batch
                        .execute()
        );

        // 1 parent INSERT + 1 parent UPDATE (mutate dirty-check) + 4 child INSERTs = 6 prepares
        assertTransactionEvents(result, 6, true);

        assertNotNull(result.getValue());
        assertEquals(orderId, result.getValue().getOrderId());

        // Parent mutation persisted
        val parentReadBack = orderLookupDao.get(orderId);
        assertEquals(999, parentReadBack.get().getAmount(), "Mutate must persist");

        // All 4 children saved
        val children = selectItems(orderId);
        assertEquals(4, children.size());
    }

    // =========================================================================
    // 20.2 READ mode — get existing entity and execute mutations
    // =========================================================================

    @Test
    void readMode_lockAndMutate() throws Exception {
        val orderId = UUID.randomUUID().toString();

        orderLookupDao.save(SanityOrder.builder()
                .orderId(orderId).customerId("original").amount(100).build());

        val result = checkpoint(() ->
                orderLookupDao.lockAndGetExecutor(orderId)
                        .mutate(parent -> {
                            parent.setCustomerId("mutated");
                            parent.setAmount(555);
                        })
                        .save(orderItemDao, parent -> SanityOrderItem.builder()
                                .orderId(parent.getOrderId()).itemName("new-child")
                                .quantity(1).price(50).build())
                        .execute()
        );

        // SELECT FOR UPDATE (1) + parent UPDATE (1) + child INSERT (1) = 3 prepares
        assertTransactionEvents(result, 3, true);

        val parentReadBack = orderLookupDao.get(orderId);
        assertEquals("mutated", parentReadBack.get().getCustomerId());
        assertEquals(555, parentReadBack.get().getAmount());

        val children = selectItems(orderId);
        assertEquals(1, children.size());
        assertEquals("new-child", children.get(0).getItemName());
    }

    // =========================================================================
    // 20.3 READ mode — entity not found throws
    // =========================================================================

    @Test
    void readMode_entityNotFound_throws() throws Exception {
        val nonExistentId = UUID.randomUUID().toString();

        val result = checkpoint(() -> {
            assertThrows(RuntimeException.class, () ->
                    orderLookupDao.lockAndGetExecutor(nonExistentId)
                            .mutate(parent -> parent.setAmount(999))
                            .execute());
            return null;
        });

        // SELECT FOR UPDATE (1 prepare), entity null → RuntimeException → rollback
        assertTransactionEvents(result, 1, false);
    }

    // =========================================================================
    // 20.4 Atomicity — INSERT mode, exception in queued operation rolls back ALL
    // =========================================================================

    @Test
    void insertMode_exceptionInQueuedOp_rollsBackAll() throws Exception {
        val orderId = UUID.randomUUID().toString();

        val order = SanityOrder.builder()
                .orderId(orderId).customerId("should-not-persist").amount(100).build();

        val result = checkpoint(() -> {
            assertThrows(RuntimeException.class, () ->
                    orderLookupDao.saveAndGetExecutor(order)
                            .save(orderItemDao, parent -> SanityOrderItem.builder()
                                    .orderId(orderId).itemName("op-1-child")
                                    .quantity(1).price(10).build())     // op 1: succeeds
                            .apply(parent -> {
                                throw new RuntimeException("op 2 explodes");
                            })                                          // op 2: throws
                            .save(orderItemDao, parent -> SanityOrderItem.builder()
                                    .orderId(orderId).itemName("op-3-child")
                                    .quantity(3).price(30).build())     // op 3: never reached
                            .execute());
            return null;
        });

        // parent INSERT (1) + child INSERT op1 (1) + op2 threw → rollback
        assertTransactionEvents(result, 2, false);

        // Parent must NOT exist (rolled back)
        val parentReadBack = orderLookupDao.get(orderId);
        assertTrue(parentReadBack.isEmpty(), "Parent must be rolled back");

        // Children must NOT exist
        val children = selectItems(orderId);
        assertTrue(children.isEmpty(), "All children must be rolled back");
    }

    // =========================================================================
    // 20.5 Atomicity — READ mode, exception in queued operation
    // =========================================================================

    @Test
    void readMode_exceptionInQueuedOp_rollsBackMutations() throws Exception {
        val orderId = UUID.randomUUID().toString();

        orderLookupDao.save(SanityOrder.builder()
                .orderId(orderId).customerId("original").amount(100).build());

        val result = checkpoint(() -> {
            assertThrows(RuntimeException.class, () ->
                    orderLookupDao.lockAndGetExecutor(orderId)
                            .mutate(parent -> parent.setCustomerId("mutated-by-op1"))  // op 1
                            .apply(parent -> {
                                throw new RuntimeException("op 2 explodes");
                            })                                                          // op 2: throws
                            .mutate(parent -> parent.setAmount(999))                    // op 3: never reached
                            .execute());
            return null;
        });

        // SELECT FOR UPDATE (1), op2 threw → rollback [mutate's update operation is not executed immediately, so only 1 prepare for SELECT FOR UPDATE]
        assertTransactionEvents(result, 1, false);

        // Parent must be in ORIGINAL state (op1 mutation rolled back)
        val readBack = orderLookupDao.get(orderId);
        assertEquals("original", readBack.get().getCustomerId(),
                "Op1 mutation must be rolled back");
        assertEquals(100, readBack.get().getAmount());
    }

    // =========================================================================
    // 20.6 Operations execute in order
    // =========================================================================

    @Test
    void insertMode_operationsExecuteInOrder() throws Exception {
        val orderId = UUID.randomUUID().toString();

        val order = SanityOrder.builder()
                .orderId(orderId).customerId("step0").amount(0).build();

        val result = checkpoint(() ->
                orderLookupDao.saveAndGetExecutor(order)
                        .mutate(parent -> parent.setCustomerId("step1"))
                        .mutate(parent -> parent.setCustomerId(parent.getCustomerId() + "-step2"))
                        .mutate(parent -> parent.setCustomerId(parent.getCustomerId() + "-step3"))
                        .execute()
        );

        // INSERT (1) + UPDATE dirty-check (1) = 2 prepares
        assertTransactionEvents(result, 2, true);

        val readBack = orderLookupDao.get(orderId);
        assertEquals("step1-step2-step3", readBack.get().getCustomerId(),
                "Operations must execute in queue order");
    }

    // =========================================================================
    // 20.8 Filter — predicate pass and fail
    // =========================================================================

    @Test
    void readMode_filterPass_operationsContinue() throws Exception {
        val orderId = UUID.randomUUID().toString();

        orderLookupDao.save(SanityOrder.builder()
                .orderId(orderId).customerId("valid").amount(500).build());

        val result = checkpoint(() ->
                orderLookupDao.lockAndGetExecutor(orderId)
                        .filter(parent -> parent.getAmount() > 100)  // passes
                        .mutate(parent -> parent.setCustomerId("after-filter"))
                        .execute()
        );

        assertTransactionEvents(result, true);

        val readBack = orderLookupDao.get(orderId);
        assertEquals("after-filter", readBack.get().getCustomerId());
    }

    @Test
    void readMode_filterFail_throwsAndRollsBack() throws Exception {
        val orderId = UUID.randomUUID().toString();

        orderLookupDao.save(SanityOrder.builder()
                .orderId(orderId).customerId("original").amount(50).build());

        val existingItem = orderItemDao.save(orderId, SanityOrderItem.builder()
                .orderId(orderId).itemName("existing-item").quantity(5).price(20).build());
        val existingItemId = existingItem.get().getId();

        val result = checkpoint(() -> {
            assertThrows(RuntimeException.class, () ->
                    orderLookupDao.lockAndGetExecutor(orderId)
                            .mutate(parent -> parent.setCustomerId("before-filter"))
                            .save(orderItemDao, parent -> SanityOrderItem.builder()
                                    .orderId(orderId).itemName("child-before-filter")
                                    .quantity(1).price(10).build())
                            .update(orderItemDao, existingItemId, item -> {
                                item.setQuantity(999);
                                item.setPrice(999);
                                return item;
                            })
                            .filter(parent -> parent.getAmount() > 100)  // fails
                            .mutate(parent -> parent.setCustomerId("should-not-reach"))
                            .execute());
            return null;
        });

        // SELECT FOR UPDATE (1) + child INSERT (1) + update's SELECT-by-id (1) = 3 prepares.
        // Filter failed before any UPDATE flush → rollback.
        assertTransactionEvents(result, 3, false);

        val readBack = orderLookupDao.get(orderId);
        assertEquals("original", readBack.get().getCustomerId(),
                "Entity must be unchanged after filter failure");

        val children = selectItems(orderId);
        assertEquals(1, children.size(),
                "Only the pre-existing item must remain; child save before filter must be rolled back");
        assertEquals("existing-item", children.get(0).getItemName());
        assertEquals(5, children.get(0).getQuantity(),
                "Update before filter must be rolled back — quantity must be unchanged");
        assertEquals(20, children.get(0).getPrice(),
                "Update before filter must be rolled back — price must be unchanged");
    }

    @Test
    void readMode_filterPass_saveAndUpdatePersist() throws Exception {
        val orderId = UUID.randomUUID().toString();

        orderLookupDao.save(SanityOrder.builder()
                .orderId(orderId).customerId("original").amount(500).build());

        val existingItem = orderItemDao.save(orderId, SanityOrderItem.builder()
                .orderId(orderId).itemName("existing-item").quantity(5).price(20).build());
        val existingItemId = existingItem.get().getId();

        val result = checkpoint(() ->
                orderLookupDao.lockAndGetExecutor(orderId)
                        .mutate(parent -> parent.setCustomerId("before-filter"))
                        .save(orderItemDao, parent -> SanityOrderItem.builder()
                                .orderId(orderId).itemName("child-before-filter")
                                .quantity(1).price(10).build())
                        .update(orderItemDao, existingItemId, item -> {
                            item.setQuantity(999);
                            item.setPrice(999);
                            return item;
                        })
                        .filter(parent -> parent.getAmount() > 100)  // passes
                        .mutate(parent -> parent.setCustomerId("after-filter"))
                        .execute()
        );

        // SELECT FOR UPDATE (1) + child INSERT (1) + update's SELECT-by-id (1)
        // + parent UPDATE dirty-check flush (1) + item UPDATE dirty-check flush (1) = 5 prepares.
        assertTransactionEvents(result, 5, true);

        val children = selectItems(orderId);
        assertEquals(2, children.size(), "Both existing and newly saved child must persist");

        val updatedExisting = children.stream()
                .filter(item -> item.getId().equals(existingItemId))
                .findFirst().orElseThrow();
        assertEquals(999, updatedExisting.getQuantity(), "Update before filter must persist");
        assertEquals(999, updatedExisting.getPrice(), "Update before filter must persist");

        val newChild = children.stream()
                .filter(item -> "child-before-filter".equals(item.getItemName()))
                .findFirst().orElseThrow();
        assertEquals(1, newChild.getQuantity());
    }

    @Test
    void readMode_filterWithCustomException() throws Exception {
        val orderId = UUID.randomUUID().toString();

        orderLookupDao.save(SanityOrder.builder()
                .orderId(orderId).customerId("original").amount(50).build());

        val result = checkpoint(() -> {
            val thrown = assertThrows(IllegalStateException.class, () ->
                    orderLookupDao.lockAndGetExecutor(orderId)
                            .filter(parent -> parent.getAmount() > 100,
                                    new IllegalStateException("amount too low"))
                            .execute());
            assertEquals("amount too low", thrown.getMessage());
            return null;
        });

        assertTransactionEvents(result, 1, false);
    }

    // =========================================================================
    // 20.9 Large operation queue
    // =========================================================================

    @Test
    void insertMode_largeOperationQueue() throws Exception {
        val orderId = UUID.randomUUID().toString();

        val order = SanityOrder.builder()
                .orderId(orderId).customerId("bulk").amount(0).build();

        var context = orderLookupDao.saveAndGetExecutor(order);
        for (int i = 1; i <= 50; i++) {
            int idx = i;
            context = context.save(orderItemDao, parent -> SanityOrderItem.builder()
                    .orderId(orderId).itemName("bulk-item-" + idx)
                    .quantity(idx).price(idx * 10).build());
        }

        val finalContext = context;
        val result = checkpoint(() -> finalContext.execute());

        // 1 parent INSERT + 50 child INSERTs = 51 prepares
        assertTransactionEvents(result, 51, true);

        val children = selectItems(orderId);
        assertEquals(50, children.size(), "All 50 children must be atomically saved");
    }

    // =========================================================================
    // RelationalDao — lockAndGetExecutor / saveAndGetExecutor
    // =========================================================================

    @Test
    void relationalDao_saveAndGetExecutor_insertMode() throws Exception {
        val parentKey = UUID.randomUUID().toString();

        val item = SanityOrderItem.builder()
                .orderId(parentKey).itemName("parent-item").quantity(1).price(100).build();

        val result = checkpoint(() ->
                orderItemDao.saveAndGetExecutor(parentKey, item)
                        .save(orderItemDao, parent -> SanityOrderItem.builder()
                                .orderId(parentKey).itemName("parent-item-2")
                                .quantity(2).price(200).build())
                        .execute()
        );

        assertTransactionEvents(result, 2, true);
        assertNotNull(result.getValue());
        assertEquals("parent-item", result.getValue().getItemName());

        val readBack = selectItems(parentKey);
        assertEquals(2, readBack.size());
        val itemNames = readBack.stream().map(SanityOrderItem::getItemName).collect(Collectors.toList());
        assertTrue(itemNames.contains("parent-item"));
        assertTrue(itemNames.contains("parent-item-2"));
    }

    @Test
    void relationalDao_lockAndGetExecutor_readMode() throws Exception {
        val parentKey = UUID.randomUUID().toString();

        orderItemDao.save(parentKey, SanityOrderItem.builder()
                .orderId(parentKey).itemName("existing").quantity(10).price(50).build());

        val criteria = DetachedCriteria.forClass(SanityOrderItem.class)
                .add(Restrictions.eq("orderId", parentKey))
                .add(Restrictions.eq("itemName", "existing"));

        val result = checkpoint(() ->
                orderItemDao.lockAndGetExecutor(parentKey, criteria)
                        .mutate(item -> item.setQuantity(99))
                        .save(orderItemDao, parent -> SanityOrderItem.builder()
                                .orderId(parentKey).itemName("parent-item-2")
                                .quantity(2).price(200).build())
                        .execute()
        );

        // SELECT FOR UPDATE (1) + UPDATE dirty-check (1) + SAVE (1) = 3 prepares
        assertTransactionEvents(result, 3, true);

        val readBack = selectItems(parentKey);
        assertEquals(2, readBack.size());
        val existingItem = readBack.stream().filter(item -> "existing".equals(item.getItemName())).findFirst().orElseThrow();
        assertEquals(99, existingItem.getQuantity());
        val newItem = readBack.stream().filter(item -> "parent-item-2".equals(item.getItemName())).findFirst().orElseThrow();
        assertEquals(2, newItem.getQuantity());
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private List<SanityOrderItem> selectItems(String orderId) throws Exception {
        val criteria = DetachedCriteria.forClass(SanityOrderItem.class)
                .add(Restrictions.eq("orderId", orderId));
        return orderItemDao.select(orderId, criteria, 0, Integer.MAX_VALUE);
    }

    // =========================================================================
    // TODO Skipped tests (require multi-threaded coordination):
    //
    // 20.7 Concurrent LockAndExecute on same entity
    //   - Two threads LockAndExecute on same entity.
    //   - READ mode uses SELECT FOR UPDATE (PESSIMISTIC_WRITE), so second thread
    //     should block until first commits, then see updated state.
    //   - INSERT mode on same @LookupKey would hit unique constraint on second thread.
    //   - Verify serialized execution, no lost updates.
    // =========================================================================
}

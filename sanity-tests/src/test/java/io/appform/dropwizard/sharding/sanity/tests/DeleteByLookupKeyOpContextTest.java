package io.appform.dropwizard.sharding.sanity.tests;

import io.appform.dropwizard.sharding.sanity.base.SanityTestBase;
import io.appform.dropwizard.sharding.sanity.entities.SanityOrder;
import lombok.val;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for the {@code DeleteByLookupKey} opContext.
 *
 * <p>Deletes an entity by its @LookupKey. Internally does SELECT FOR UPDATE
 * then DELETE if found.
 *
 * <p>Public methods:
 * <ul>
 *   <li>{@code LookupDao.delete(String id)}</li>
 * </ul>
 */
class DeleteByLookupKeyOpContextTest extends SanityTestBase {

    @BeforeEach
    void cleanTables() throws Exception {
        truncateAllTables();
    }

    // -------------------------------------------------------------------------
    // 15.1 Delete and verify gone
    // -------------------------------------------------------------------------

    @Test
    void delete_existingEntity_noLongerReadable() throws Exception {
        val orderId = UUID.randomUUID().toString();

        orderLookupDao.save(SanityOrder.builder()
                .orderId(orderId).customerId("to-delete").amount(100).build());

        assertTrue(orderLookupDao.get(orderId).isPresent(), "Entity must exist before delete");

        // Delete
        val result = checkpoint(() -> {
            try {
                return orderLookupDao.delete(orderId);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });

        // SELECT FOR UPDATE (1) + DELETE (1) = 2 prepares
        assertTransactionEvents(result, 2, true);
        assertTrue(result.getValue(), "delete should return true");

        // Get after delete — must be empty
        val readBack = orderLookupDao.get(orderId);
        assertTrue(readBack.isEmpty(), "Entity must not be visible after delete");

        // Also verify not on shard via direct JDBC
        int shard = shardForKey(orderId);
        assertFalse(existsOnShard(shard, orderId),
                "Entity must not exist on shard after delete");
    }

    // -------------------------------------------------------------------------
    // 15.2 Delete non-existent key — returns false
    // -------------------------------------------------------------------------

    @Test
    void delete_nonExistentKey_returnsFalse() throws Exception {
        val result = checkpoint(() -> {
            try {
                return orderLookupDao.delete(UUID.randomUUID().toString());
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });

        // SELECT FOR UPDATE (1), entity not found → returns false, committed
        assertTransactionEvents(result, 1, true);
        assertFalse(result.getValue(), "delete on non-existent key should return false");
    }
}

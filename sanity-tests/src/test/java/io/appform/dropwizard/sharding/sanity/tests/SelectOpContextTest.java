package io.appform.dropwizard.sharding.sanity.tests;

import io.appform.dropwizard.sharding.sanity.base.SanityTestBase;
import io.appform.dropwizard.sharding.sanity.entities.SanityOrder;
import io.appform.dropwizard.sharding.sanity.entities.SanityOrderItem;
import lombok.val;
import org.hibernate.criterion.DetachedCriteria;
import org.hibernate.criterion.Order;
import org.hibernate.criterion.Restrictions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@code RelationalDao.select()} operation context.
 *
 * <p>Uses {@link SanityOrderItem} with parentKey-based shard routing.
 * All entities share the same parentKey to ensure they land on the same shard.
 */
class SelectOpContextTest extends SanityTestBase {

    @BeforeEach
    void cleanTables() throws Exception {
        truncateAllTables();
    }

    // -------------------------------------------------------------------------
    // 5.1 Select multiple entities
    // -------------------------------------------------------------------------

    @Test
    void select_multipleEntities_criteriaFilters() throws Exception {
        val parentKey = UUID.randomUUID().toString();

        // Save 10 items: 5 with price < 100, 5 with price >= 100
        val items = IntStream.rangeClosed(1, 10)
                .mapToObj(i -> SanityOrderItem.builder()
                        .orderId(parentKey)
                        .itemName("item-" + i)
                        .quantity(i)
                        .price(i <= 5 ? 50 : 200)
                        .build())
                .collect(Collectors.toList());
        orderItemDao.saveAll(parentKey, items);

        // Select only the cheap ones (price < 100)
        val result = checkpoint(() -> {
            try {
                val criteria = DetachedCriteria.forClass(SanityOrderItem.class)
                        .add(Restrictions.eq("orderId", parentKey))
                        .add(Restrictions.lt("price", 100));
                return orderItemDao.select(parentKey, criteria, 0, Integer.MAX_VALUE);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });

        assertTransactionEvents(result, 1, true);
        assertEquals(5, result.getValue().size(), "Exactly 5 cheap items should match");
        assertTrue(result.getValue().stream().allMatch(it -> it.getPrice() == 50));
    }

    // -------------------------------------------------------------------------
    // 5.2 Select with pagination
    // -------------------------------------------------------------------------

    @Test
    void select_withPagination() throws Exception {
        val parentKey = UUID.randomUUID().toString();

        // Save 18 items
        val items = IntStream.rangeClosed(1, 18)
                .mapToObj(i -> SanityOrderItem.builder()
                        .orderId(parentKey)
                        .itemName("page-item-" + String.format("%02d", i))
                        .quantity(i)
                        .price(i * 10)
                        .build())
                .collect(Collectors.toList());
        orderItemDao.saveAll(parentKey, items);

        val criteria = DetachedCriteria.forClass(SanityOrderItem.class)
                .add(Restrictions.eq("orderId", parentKey))
                .addOrder(Order.asc("itemName"));

        int pageSize = 5;
        int totalFetched = 0;
        int offset = 0;

        // Page 1: items 1-5
        val page1 = selectPage(parentKey, criteria, offset, pageSize);
        assertTransactionEvents(page1, 1, true);
        assertEquals(5, page1.getValue().size());
        totalFetched += page1.getValue().size();
        offset += pageSize;

        // Page 2: items 6-10
        val page2 = selectPage(parentKey, criteria, offset, pageSize);
        assertTransactionEvents(page2, 1, true);
        assertEquals(5, page2.getValue().size());
        totalFetched += page2.getValue().size();
        offset += pageSize;

        // Verify no overlap between page 1 and page 2
        val page1Names = page1.getValue().stream()
                .map(SanityOrderItem::getItemName).collect(Collectors.toSet());
        val page2Names = page2.getValue().stream()
                .map(SanityOrderItem::getItemName).collect(Collectors.toSet());
        assertTrue(page1Names.stream().noneMatch(page2Names::contains),
                "Pages must not overlap");

        // Page 3: items 11-15
        val page3 = selectPage(parentKey, criteria, offset, pageSize);
        assertEquals(5, page3.getValue().size());
        totalFetched += page3.getValue().size();
        offset += pageSize;

        // Page 4: items 16-18 (partial page)
        val page4 = selectPage(parentKey, criteria, offset, pageSize);
        assertEquals(3, page4.getValue().size());
        totalFetched += page4.getValue().size();
        offset += page4.getValue().size();

        // Page 5: empty (exhausted)
        val page5 = selectPage(parentKey, criteria, offset, pageSize);
        assertEquals(0, page5.getValue().size());

        assertEquals(18, totalFetched, "Total across all pages must equal 18");
    }

    // -------------------------------------------------------------------------
    // 5.3 Select returns empty list for no matches
    // -------------------------------------------------------------------------

    @Test
    void select_noMatches_returnsEmptyList() throws Exception {
        val parentKey = UUID.randomUUID().toString();

        val result = checkpoint(() -> {
            try {
                val criteria = DetachedCriteria.forClass(SanityOrderItem.class)
                        .add(Restrictions.eq("orderId", parentKey));
                return orderItemDao.select(parentKey, criteria, 0, Integer.MAX_VALUE);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });

        assertTransactionEvents(result, 1, true);
        assertTrue(result.getValue().isEmpty(), "Select with no matches must return empty list");
    }

    // -------------------------------------------------------------------------
    // 5.4 Select sees latest committed state
    // -------------------------------------------------------------------------

    @Test
    void select_seesLatestCommittedState() throws Exception {
        val parentKey = UUID.randomUUID().toString();

        // Save 5 items
        val items = IntStream.rangeClosed(1, 5)
                .mapToObj(i -> SanityOrderItem.builder()
                        .orderId(parentKey)
                        .itemName("item-" + i)
                        .quantity(i)
                        .price(100)
                        .build())
                .collect(Collectors.toList());
        orderItemDao.saveAll(parentKey, items);

        // Verify all 5 exist
        assertEquals(5, selectAll(parentKey).size());

        // Update item-1's price to 999
        val item1 = selectAll(parentKey).stream()
                .filter(it -> "item-1".equals(it.getItemName()))
                .findFirst().orElseThrow();
        orderItemDao.update(parentKey, item1.getId(), existing -> {
            existing.setPrice(999);
            return existing;
        });

        // Select all — must see the update
        val result = checkpoint(() -> {
            try {
                return selectAll(parentKey);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });

        assertTransactionEvents(result, 1, true);
        assertEquals(5, result.getValue().size());
        val updatedItem = result.getValue().stream()
                .filter(it -> "item-1".equals(it.getItemName()))
                .findFirst().orElseThrow();
        assertEquals(999, updatedItem.getPrice(),
                "Select must see the updated price, not stale data");
    }

    // -------------------------------------------------------------------------
    // 5.5 Select with afterSelect transformation
    // -------------------------------------------------------------------------

    @Test
    void select_withAfterSelect_transformationApplied() throws Exception {
        val parentKey = UUID.randomUUID().toString();

        val items = IntStream.rangeClosed(1, 3)
                .mapToObj(i -> SanityOrderItem.builder()
                        .orderId(parentKey)
                        .itemName("item-" + i)
                        .quantity(i)
                        .price(i * 100)
                        .build())
                .collect(Collectors.toList());
        orderItemDao.saveAll(parentKey, items);

        // select with handler that transforms the result list
        val result = checkpoint(() -> {
            try {
                val criteria = DetachedCriteria.forClass(SanityOrderItem.class)
                        .add(Restrictions.eq("orderId", parentKey));
                return orderItemDao.select(parentKey, criteria, 0, Integer.MAX_VALUE,
                        list -> list.stream()
                                .map(SanityOrderItem::getItemName)
                                .collect(Collectors.joining(",")));
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });

        assertTransactionEvents(result, 1, true);
        // Order is not guaranteed, so just check all names are present
        val csv = result.getValue();
        assertTrue(csv.contains("item-1"), "Transformed result must contain item-1");
        assertTrue(csv.contains("item-2"), "Transformed result must contain item-2");
        assertTrue(csv.contains("item-3"), "Transformed result must contain item-3");
    }

    // -------------------------------------------------------------------------
    // 5.6 Select with afterSelect mutation — should NOT persist (readOnly)
    // -------------------------------------------------------------------------

    @Test
    void select_withAfterSelect_mutationDoesNotPersist() throws Exception {
        val parentKey = UUID.randomUUID().toString();

        orderItemDao.save(parentKey, SanityOrderItem.builder()
                .orderId(parentKey)
                .itemName("immutable-item")
                .quantity(10)
                .price(100)
                .build());

        // Handler mutates entities in the result list
        val result = checkpoint(() -> {
            try {
                val criteria = DetachedCriteria.forClass(SanityOrderItem.class)
                        .add(Restrictions.eq("orderId", parentKey));
                return orderItemDao.select(parentKey, criteria, 0, Integer.MAX_VALUE,
                        list -> {
                            list.forEach(item -> item.setPrice(9999));
                            return list;
                        });
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });

        // Only 1 PREPARE_STATEMENT (SELECT). No UPDATE — session is read-only.
        assertTransactionEvents(result, 1, true);

        // Verify DB state is NOT mutated
        val readBack = selectAll(parentKey);
        assertEquals(1, readBack.size());
        assertEquals(100, readBack.get(0).getPrice(),
                "Mutation in afterSelect handler must NOT persist — session is read-only");
    }

    // -------------------------------------------------------------------------
    // 5.7 Select with non-unique criteria returns multiple rows
    // -------------------------------------------------------------------------

    @Test
    void select_nonUniqueCriteria_returnsMultipleRows() throws Exception {
        val parentKey = UUID.randomUUID().toString();

        // Save 3 items with same orderId but different itemNames
        for (int i = 1; i <= 3; i++) {
            orderItemDao.save(parentKey, SanityOrderItem.builder()
                    .orderId(parentKey)
                    .itemName("multi-item-" + i)
                    .quantity(i)
                    .price(i * 100)
                    .build());
        }

        // Select by orderId — matches all 3
        val result = checkpoint(() -> {
            try {
                val criteria = DetachedCriteria.forClass(SanityOrderItem.class)
                        .add(Restrictions.eq("orderId", parentKey));
                return orderItemDao.select(parentKey, criteria, 0, Integer.MAX_VALUE);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });

        assertTransactionEvents(result, 1, true);
        assertEquals(3, result.getValue().size(), "Select must return all 3 matching rows");
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private java.util.List<SanityOrderItem> selectAll(String parentKey) throws Exception {
        val criteria = DetachedCriteria.forClass(SanityOrderItem.class)
                .add(Restrictions.eq("orderId", parentKey));
        return orderItemDao.select(parentKey, criteria, 0, Integer.MAX_VALUE);
    }

    private CheckpointResult<java.util.List<SanityOrderItem>> selectPage(
            String parentKey, DetachedCriteria criteria, int offset, int pageSize) {
        return checkpoint(() -> {
            try {
                return orderItemDao.select(parentKey, criteria, offset, pageSize);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
    }

    // =========================================================================
    // LookupDao methods that use Select OpContext internally
    // =========================================================================

    // -------------------------------------------------------------------------
    // scatterGather(DetachedCriteria) — queries ALL shards, merges results
    // -------------------------------------------------------------------------

    @Test
    void lookupDao_scatterGather_returnsFromAllShards() throws Exception {
        // Save orders with different orderId values — they'll land on different shards
        val orderId1 = UUID.randomUUID().toString();
        val orderId2 = UUID.randomUUID().toString();
        val orderId3 = UUID.randomUUID().toString();

        orderLookupDao.save(SanityOrder.builder().orderId(orderId1).customerId("c1").amount(100).build());
        orderLookupDao.save(SanityOrder.builder().orderId(orderId2).customerId("c2").amount(200).build());
        orderLookupDao.save(SanityOrder.builder().orderId(orderId3).customerId("c3").amount(300).build());

        // scatterGather with criteria matching amount > 50 (all 3)
        val criteria = DetachedCriteria.forClass(SanityOrder.class)
                .add(Restrictions.gt("amount", 50));
        val result = checkpoint(() -> orderLookupDao.scatterGather(criteria));

        assertTransactionEvents(result, true);
        assertEquals(3, result.getValue().size(), "scatterGather must return entities from all shards");
    }

    @Test
    void lookupDao_scatterGather_withFilteringCriteria() throws Exception {
        val orderId1 = UUID.randomUUID().toString();
        val orderId2 = UUID.randomUUID().toString();

        orderLookupDao.save(SanityOrder.builder().orderId(orderId1).customerId("c1").amount(100).build());
        orderLookupDao.save(SanityOrder.builder().orderId(orderId2).customerId("c2").amount(500).build());

        // Only amount > 200
        val criteria = DetachedCriteria.forClass(SanityOrder.class)
                .add(Restrictions.gt("amount", 200));
        val result = checkpoint(() -> orderLookupDao.scatterGather(criteria));

        assertTransactionEvents(result, true);
        assertEquals(1, result.getValue().size());
        assertEquals(500, result.getValue().get(0).getAmount());
    }

    @Test
    void lookupDao_scatterGather_noMatches_returnsEmpty() throws Exception {
        val orderId1 = UUID.randomUUID().toString();
        val orderId2 = UUID.randomUUID().toString();

        orderLookupDao.save(SanityOrder.builder().orderId(orderId1).customerId("c1").amount(100).build());
        orderLookupDao.save(SanityOrder.builder().orderId(orderId2).customerId("c2").amount(500).build());


        val criteria = DetachedCriteria.forClass(SanityOrder.class)
                .add(Restrictions.eq("customerId", "nonexistent"));
        val result = checkpoint(() -> orderLookupDao.scatterGather(criteria));

        assertTransactionEvents(result, true);
        assertTrue(result.getValue().isEmpty(), "scatterGather with no matches must return empty list");
    }

    // -------------------------------------------------------------------------
    // scatterGather(QuerySpec, start, numRows) — paginated scatter-gather
    // -------------------------------------------------------------------------

    @Test
    void lookupDao_scatterGather_withQuerySpec() throws Exception {
        // Save 5 orders
        for (int i = 1; i <= 5; i++) {
            orderLookupDao.save(SanityOrder.builder()
                    .orderId(UUID.randomUUID().toString())
                    .customerId("scatter-" + i)
                    .amount(i * 100)
                    .build());
        }

        val result = checkpoint(() -> orderLookupDao.scatterGather(
                (root, query, builder) -> query.where(builder.gt(root.get("amount"), 0)),
                0, 10));

        assertTransactionEvents(result, true);
        assertEquals(5, result.getValue().size(), "scatterGather with QuerySpec must return all matching");
    }

    // -------------------------------------------------------------------------
    // RelationalDao methods using Select OpContext
    // -------------------------------------------------------------------------

    // -------------------------------------------------------------------------
    // RelationalDao.scatterGather(DetachedCriteria, start, numResults)
    // -------------------------------------------------------------------------

    @Test
    void relationalDao_scatterGather() throws Exception {
        val parentKey1 = UUID.randomUUID().toString();
        val parentKey2 = UUID.randomUUID().toString();

        orderItemDao.save(parentKey1, SanityOrderItem.builder()
                .orderId(parentKey1).itemName("item-1").quantity(1).price(100).build());
        orderItemDao.save(parentKey2, SanityOrderItem.builder()
                .orderId(parentKey2).itemName("item-2").quantity(2).price(200).build());

        val criteria = DetachedCriteria.forClass(SanityOrderItem.class);
        val result = checkpoint(() -> orderItemDao.scatterGather(criteria, 0, 100));

        assertTransactionEvents(result, true);
        assertEquals(2, result.getValue().size(), "scatterGather must return entities from all shards");
    }
}

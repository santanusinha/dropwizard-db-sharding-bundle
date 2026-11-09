package io.appform.dropwizard.sharding.sanity.tests;

/**
 * Tests for the {@code UpdateByQuery} opContext.
 *
 * <p>Executes a named query (HQL) update and returns the affected row count.
 *
 * <p>Public methods:
 * <ul>
 *   <li>{@code LookupDao.updateUsingQuery(id, UpdateOperationMeta)}</li>
 *   <li>{@code RelationalDao.updateUsingQuery(parentKey, UpdateOperationMeta)}</li>
 *   <li>{@code LockedContext.updateUsingQuery(RelationalDao, UpdateOperationMeta)}</li>
 * </ul>
 *
 * <h3>PREREQUISITE</h3>
 * UpdateByQuery uses Hibernate named queries ({@code @NamedQuery} on the entity class).
 * The current test entities ({@code SanityOrder}, {@code SanityOrderItem}) do NOT have
 * named queries defined. To enable these tests:
 *
 * <p>1. Add to SanityOrderItem:
 * <pre>
 * {@literal @}NamedQueries({
 *     {@literal @}NamedQuery(
 *         name = "SanityOrderItem.updatePriceByOrderId",
 *         query = "UPDATE SanityOrderItem SET price = :newPrice WHERE orderId = :orderId"
 *     )
 * })
 * </pre>
 *
 * <p>2. Then use:
 * <pre>
 *   UpdateOperationMeta meta = UpdateOperationMeta.builder()
 *       .queryName("SanityOrderItem.updatePriceByOrderId")
 *       .params(Map.of("newPrice", 999, "orderId", parentKey))
 *       .build();
 *   int affected = orderItemDao.updateUsingQuery(parentKey, meta);
 * </pre>
 *
 * <h3>Planned test cases</h3>
 * <ul>
 *   <li>14.1 Bulk update affects correct rows — UPDATE WHERE status=A, verify 5 changed, 5 unchanged</li>
 *   <li>14.2 Returns correct affected row count</li>
 *   <li>14.3 No matches — returns 0, no side effects</li>
 *   <li>14.4 Atomicity — UpdateByQuery within LockedContext:
 *       if subsequent operation fails, the bulk update rolls back;
 *       if UpdateByQuery fails, all prior writes in the LockedContext roll back</li>
 *   <li>14.5 UpdateByQuery and Get — subsequent Get in same/new session sees updated data</li>
 * </ul>
 *
 * <p>TODO: Add {@code @NamedQuery} to test entities and implement these test cases.
 */
class UpdateByQueryOpContextTest {
    // Tests require @NamedQuery on SanityOrderItem — see class javadoc.
}

package io.appform.dropwizard.sharding.query;

/**
 * A functional interface that produces a {@link QuerySpec} at execution time, given access to the parent entity.
 *
 * <p>This is useful in scenarios where query criteria depend on data from the parent entity that is only available
 * after it has been fetched from the database. For example, a child query might need to include the parent's
 * {@code partitionId} in its WHERE clause for index optimisation, but the parent hasn't been retrieved yet at
 * query-specification time.</p>
 *
 * <p>Unlike a plain {@link QuerySpec} which is defined eagerly, a {@code QuerySpecFactory} defers construction
 * of the query specification until the parent entity is available.</p>
 *
 * @param <P> The type of the parent entity from which query parameters are derived.
 * @param <T> The type of the child entity being queried.
 * @param <U> The result type of the query specification.
 *
 * @see QuerySpec
 */
@FunctionalInterface
public interface QuerySpecFactory<P, T, U> {

    /**
     * Creates a {@link QuerySpec} using information from the provided parent entity.
     *
     * @param parent The parent entity, already fetched from the database.
     * @return A {@link QuerySpec} to be used for querying child entities.
     */
    QuerySpec<T, U> create(P parent);
}

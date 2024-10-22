/**
 * The `QuerySpec` interface defines a functional contract for applying query specifications
 * to a JPA (Java Persistence API) Criteria API query.
 *
 * <p>Implementations of this interface can define custom query specifications and apply them
 * to a JPA Criteria query, typically used for building database queries dynamically.
 *
 * @param <T> The type of the entity for which the query specification is applied.
 * @param <U> The type of the result expected from the query.
 *
 * @see javax.persistence.criteria.CriteriaBuilder
 * @see javax.persistence.criteria.CriteriaQuery
 * @see javax.persistence.criteria.Root
 *
 * @author Your Name
 * @version 1.0
 * @since 2023-09-30
 */

package io.appform.dropwizard.sharding.query;


import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Root;

/**
 * A functional interface representing a query specification.
 *
 * @param <T> The type of the entity being queried.
 * @param <U> The result type of the query specification.
 *
 * <p>
 * The {@code QuerySpec} functional interface defines a contract for applying a query specification to a JPA Criteria
 * Query. It is typically used to specify filtering and shaping of query results.
 * </p>
 *
 * <p>
 * Implementations of this interface must provide an {@link #apply(Root, CriteriaQuery, CriteriaBuilder)} method,
 * which takes a query root, a query, and a criteria builder as parameters. Within this method, developers can specify
 * various criteria and conditions to be applied to the query.
 * </p>
 *
 * <p>
 * This interface allows for custom query specifications to be defined and applied to queries, enabling flexibility
 * in constructing complex database queries.
 * </p>
 */
@FunctionalInterface
public interface QuerySpec<T, U> {

    /**
     * Applies the query specification to a JPA Criteria Query.
     *
     * @param queryRoot       The root entity for the query.
     * @param query           The Criteria Query object to which the specification is applied.
     * @param criteriaBuilder The CriteriaBuilder for constructing query criteria.
     */
    void apply(Root<T> queryRoot, CriteriaQuery<U> query, CriteriaBuilder criteriaBuilder);
}

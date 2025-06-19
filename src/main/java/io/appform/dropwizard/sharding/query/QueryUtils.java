package io.appform.dropwizard.sharding.query;

import lombok.experimental.UtilityClass;

import javax.persistence.criteria.CriteriaBuilder;
import javax.persistence.criteria.Predicate;
import javax.persistence.criteria.Root;
import java.util.Collection;

/**
 * A utility class providing common query-related utility methods for constructing predicates in JPA Criteria Queries.
 *
 * <p>
 * The {@code QueryUtils} utility class contains static methods that simplify the creation of predicates
 * for filtering and querying JPA entities
 * </p>
 *
 * <p>
 * This class is marked with the {@link lombok.experimental.UtilityClass} annotation, indicating that it should not be instantiated
 * and is designed for static method access only.
 * </p>
 */
@UtilityClass
public class QueryUtils {

    /**
     * Creates a predicate for equality filtering.
     *
     * @param <T>            The type of the entity being queried.
     * @param criteriaBuilder The CriteriaBuilder for constructing query criteria.
     * @param queryRoot       The root entity for the query.
     * @param column          The name of the column or attribute to filter on.
     * @param value           The value to compare for equality.
     * @return A predicate for equality filtering.
     */
    public static <T> Predicate equalityFilter(final CriteriaBuilder criteriaBuilder,
                                               final Root<T> queryRoot,
                                               final String column,
                                               final Object value) {
        return criteriaBuilder.equal(queryRoot.get(column), value);
    }

    /**
     * Creates a predicate for inequality filtering.
     *
     * @param <T>            The type of the entity being queried.
     * @param criteriaBuilder The CriteriaBuilder for constructing query criteria.
     * @param queryRoot       The root entity for the query.
     * @param column          The name of the column or attribute to filter on.
     * @param value           The value to compare for inequality.
     * @return A predicate for inequality filtering.
     */
    public static <T> Predicate notEqualFilter(final CriteriaBuilder criteriaBuilder,
                                               final Root<T> queryRoot,
                                               final String column,
                                               final Object value) {
        return criteriaBuilder.notEqual(queryRoot.get(column), value);
    }

    /**
     * Creates a predicate for "in" clause filtering.
     *
     * @param <T>     The type of the entity being queried.
     * @param queryRoot The root entity for the query.
     * @param column    The name of the column or attribute to filter on.
     * @param values    A collection of values to check for inclusion in the "in" clause.
     * @return A predicate for "in" clause filtering.
     */
    public static <T> Predicate inFilter(final Root<T> queryRoot,
                                         final String column,
                                         final Collection<?> values) {
        return queryRoot.get(column).in(values);
    }
}

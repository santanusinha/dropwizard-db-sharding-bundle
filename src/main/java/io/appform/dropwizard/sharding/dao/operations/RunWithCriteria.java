package io.appform.dropwizard.sharding.dao.operations;

import io.appform.dropwizard.sharding.query.QuerySpec;
import lombok.Builder;
import lombok.Data;
import org.hibernate.Session;
import org.hibernate.criterion.DetachedCriteria;

import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Run a query inside this shard and return resulting list.
 * <p>
 * This operation supports two execution paths:
 * <ul>
 *   <li><b>DetachedCriteria path (legacy Hibernate API):</b> Requires both {@code detachedCriteria} and {@code handler}.
 *       The handler function executes the criteria query.</li>
 *   <li><b>QuerySpec path (modern JPA Criteria API):</b> Requires both {@code querySpec} and {@code querySpecHandler}.
 *       The querySpecHandler supplier executes the QuerySpec query.</li>
 * </ul>
 * <p>
 * The two paths are mutually exclusive - provide fields for only one path.
 * An {@link IllegalStateException} will be thrown at execution time if neither path has complete parameters,
 * or if parameters are mixed between paths.
 *
 * @param <T> Return type on performing the operation.
 */
@Data
@Builder
public class RunWithCriteria<T> extends OpContext<T> {

  private Function<DetachedCriteria, T> handler;
  private DetachedCriteria detachedCriteria;
  private QuerySpec<?, ?> querySpec;
  private Supplier<T> querySpecHandler;

  @Override
  public T apply(Session session) {
    if (detachedCriteria != null && handler != null) {
      return handler.apply(detachedCriteria);
    }

    if (querySpec != null && querySpecHandler != null) {
      return querySpecHandler.get();
    }

    throw new IllegalStateException(
        "RunWithCriteria requires either (detachedCriteria + handler) OR (querySpec + querySpecHandler). " +
        "All fields are null.");
  }

  @Override
  public OpType getOpType() {
    return OpType.RUN_WITH_CRITERIA;
  }

  @Override
  public <R> R visit(OpContextVisitor<R> visitor) {
    return visitor.visit(this);
  }
}

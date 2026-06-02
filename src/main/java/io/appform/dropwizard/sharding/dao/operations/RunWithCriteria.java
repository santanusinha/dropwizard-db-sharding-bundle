package io.appform.dropwizard.sharding.dao.operations;

import lombok.Builder;
import lombok.Data;
import lombok.NonNull;
import org.hibernate.Session;

import java.util.function.Function;

/**
 * Run a query inside this shard and return resulting list.
 * <p>
 * This operation is generic over the criteria type, supporting both legacy Hibernate API
 * (DetachedCriteria) and modern JPA Criteria API (QuerySpec), as well as any future
 * criteria types.
 *
 * @param <T> Return type on performing the operation.
 * @param <C> Type of criteria used to query (DetachedCriteria, QuerySpec, etc.).
 */
@Data
@Builder
public class RunWithCriteria<T, C> extends OpContext<T> {

  @NonNull
  private C criteria;

  @NonNull
  private Function<C, T> handler;

  @Override
  public T apply(Session session) {
    return handler.apply(criteria);
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

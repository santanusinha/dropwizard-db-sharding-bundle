package io.appform.dropwizard.sharding.dao.operations;

import io.appform.dropwizard.sharding.query.QuerySpec;
import lombok.Builder;
import lombok.Data;
import org.hibernate.Session;
import org.hibernate.criterion.DetachedCriteria;

import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Run a query with given criteria inside this shard and returns resulting list.
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
    if (detachedCriteria != null) {
      return handler.apply(detachedCriteria);
    } else if (querySpec != null && querySpecHandler != null) {
      return querySpecHandler.get();
    }
    throw new IllegalStateException("Either detachedCriteria or querySpec must be provided");
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

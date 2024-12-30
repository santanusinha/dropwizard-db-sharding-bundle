package io.appform.dropwizard.sharding.dao.operations;

import lombok.Builder;
import lombok.Data;
import lombok.NonNull;
import org.hibernate.Session;
import org.hibernate.criterion.DetachedCriteria;

import java.util.function.Function;

/**
 * Run a query with given criteria inside this shard and returns resulting list.
 *
 * @param <T> Return type on performing the operation.
 */
@Data
@Builder
public class RunWithCriteria<T> extends OpContext<T> {

  @NonNull
  private Function<DetachedCriteria, T> handler;
  @NonNull
  private DetachedCriteria detachedCriteria;

  @Override
  public T apply(Session session) {
    return handler.apply(detachedCriteria);
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

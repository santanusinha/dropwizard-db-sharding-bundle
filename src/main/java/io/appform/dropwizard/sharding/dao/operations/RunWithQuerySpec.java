package io.appform.dropwizard.sharding.dao.operations;

import io.appform.dropwizard.sharding.query.QuerySpec;
import lombok.Builder;
import lombok.Data;
import lombok.NonNull;
import org.hibernate.Session;

import java.util.function.Function;

/**
 * Run a query with given criteria inside this shard and returns resulting list.
 *
 * @param <T> Return type on performing the operation.
 */
@Data
@Builder
public class RunWithQuerySpec<U, T> extends OpContext<T> {

  @NonNull
  private Function<QuerySpec<U, U>, T> handler;
  @NonNull
  private QuerySpec<U, U> querySpec;

  @Override
  public T apply(Session session) {
    return handler.apply(querySpec);
  }

  @Override
  public OpType getOpType() {
    return OpType.RUN_WITH_QUERY_SPEC;
  }

  @Override
  public <R> R visit(OpContextVisitor<R> visitor) {
    return visitor.visit(this);
  }
}

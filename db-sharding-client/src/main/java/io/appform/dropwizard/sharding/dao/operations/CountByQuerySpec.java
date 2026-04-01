package io.appform.dropwizard.sharding.dao.operations;

import io.appform.dropwizard.sharding.query.QuerySpec;
import lombok.Builder;
import lombok.Data;
import lombok.NonNull;
import org.hibernate.Session;

import java.util.function.Function;

/**
 * Returns count of records matching given query spec for a shard.
 */
@Data
@Builder
public class CountByQuerySpec extends OpContext<Long> {

  @NonNull
  private QuerySpec querySpec;

  @NonNull
  private Function<QuerySpec, Long> counter;

  @Override
  public Long apply(Session session) {
    return counter.apply(querySpec);
  }

  @Override
  public OpType getOpType() {
    return OpType.COUNT_BY_QUERY_SPEC;
  }

  @Override
  public <R> R visit(OpContextVisitor<R> visitor) {
    return visitor.visit(this);
  }
}

package io.appform.dropwizard.sharding.dao.operations;

import lombok.Builder;
import lombok.Data;
import lombok.NonNull;
import org.hibernate.Session;
import org.hibernate.criterion.DetachedCriteria;

import java.util.function.Function;

/**
 * Returns count of records matching given criteria for a shard.
 */
@Data
@Builder
public class Count extends OpContext<Long> {

  @NonNull
  private DetachedCriteria criteria;

  @NonNull
  private Function<DetachedCriteria, Long> counter;

  @Override
  public Long apply(Session session) {
    return counter.apply(criteria);
  }

  @Override
  public OpType getOpType() {
    return OpType.COUNT;
  }

  @Override
  public <R> R visit(OpContextVisitor<R> visitor) {
    return visitor.visit(this);
  }
}

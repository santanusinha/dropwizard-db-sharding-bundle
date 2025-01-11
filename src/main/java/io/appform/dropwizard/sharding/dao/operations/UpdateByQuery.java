package io.appform.dropwizard.sharding.dao.operations;

import io.appform.dropwizard.sharding.dao.UpdateOperationMeta;
import lombok.Builder;
import lombok.Data;
import lombok.NonNull;
import org.hibernate.Session;

import java.util.function.Function;

;

/**
 * Performs update operation with named query and returns count of updates.
 */
@Data
@Builder
public class UpdateByQuery extends OpContext<Integer> {

  @NonNull
  private Function<UpdateOperationMeta, Integer> updater;
  @NonNull
  private UpdateOperationMeta updateOperationMeta;

  @Override
  public Integer apply(Session session) {
    return updater.apply(updateOperationMeta);
  }

  @Override
  public OpType getOpType() {
    return OpType.UPDATE_BY_QUERY;
  }

  @Override
  public <R> R visit(OpContextVisitor<R> visitor) {
    return visitor.visit(this);
  }
}

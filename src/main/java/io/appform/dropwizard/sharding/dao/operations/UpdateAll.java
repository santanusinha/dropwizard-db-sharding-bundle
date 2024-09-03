package io.appform.dropwizard.sharding.dao.operations;

import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Function;
import lombok.Builder;
import lombok.Data;
import lombok.NonNull;
import org.hibernate.Session;

/**
 * Get all the entities by criteria. Iterate over them, mutate it and persist to DB.
 *
 * @param <T> return type of the entity to be updated.
 */
@Data
@Builder
public class UpdateAll<T> extends OpContext<Boolean> {

  @NonNull
  private SelectParam<T> selectParam;
  @NonNull
  private Function<SelectParam<T>, List<T>> selector;
  @Builder.Default
  private Function<T, T> mutator = t -> t;
  @NonNull
  private BiConsumer<T, T> updater;

  @Override
  public Boolean apply(Session session) {
    List<T> entityList = selector.apply(selectParam);
    if (entityList == null || entityList.isEmpty()) {
      return false;
    }
    for (T oldEntity : entityList) {
      if (null == oldEntity) {
        return false;
      }
      T newEntity = mutator.apply(oldEntity);
      if (null == newEntity) {
        return false;
      }
      updater.accept(oldEntity, newEntity);
    }
    return true;
  }

  @Override
  public OpType getOpType() {
    return OpType.UPDATE_ALL;
  }

  @Override
  public <R> R visit(OpContextVisitor<R> visitor) {
    return visitor.visit(this);
  }
}

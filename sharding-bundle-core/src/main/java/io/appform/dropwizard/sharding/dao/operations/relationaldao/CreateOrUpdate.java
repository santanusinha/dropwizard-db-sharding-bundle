package io.appform.dropwizard.sharding.dao.operations.relationaldao;

import io.appform.dropwizard.sharding.dao.operations.OpContext;
import io.appform.dropwizard.sharding.dao.operations.OpType;
import lombok.Builder;
import lombok.Data;
import lombok.NonNull;
import lombok.val;
import org.hibernate.Session;
import org.hibernate.criterion.DetachedCriteria;

import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;

/**
 * Acquire lock on an entity.
 * If entity present, performs mutation and updates it.
 * Else create the entity using the given @Supplier entityGenerator.
 *
 * @param <T> Type of entity on which operation being performed.
 */
@Data
@Builder
public class CreateOrUpdate<T> extends OpContext<T> {

  @NonNull DetachedCriteria criteria;
  UnaryOperator<T> mutator;
  Supplier<T> entityGenerator;
  private Function<DetachedCriteria, T> getLockedForWrite;
  private Function<DetachedCriteria, T> getter;
  private Function<T, T> saver;
  private BiConsumer<T, T> updater;

  @Override
  public T apply(Session session) {
    T result = getLockedForWrite.apply(criteria);

    if (null == result) {
      val newEntity = entityGenerator.get();
      if (null != newEntity) {
        return saver.apply(newEntity);
      }
      return null;
    }
    val updated = mutator.apply(result);
    if (null != updated) {
      updater.accept(result, updated);
    }
    return getter.apply(criteria);
  }

  @Override
  public OpType getOpType() {
    return OpType.CREATE_OR_UPDATE;
  }

  @Override
  public <R> R visit(OpContextVisitor<R> visitor) {
    return visitor.visit(this);
  }
}

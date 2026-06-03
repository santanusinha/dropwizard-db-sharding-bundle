package io.appform.dropwizard.sharding.dao.operations;

import lombok.Builder;
import lombok.Data;
import lombok.NonNull;
import org.hibernate.Session;

import java.util.function.Function;
import java.util.function.UnaryOperator;

/**
 * Persists a child entity to the DB along with a typed reference to its parent entity.
 * <p>
 * This OpContext is used when a child entity is saved within a
 * {@link io.appform.dropwizard.sharding.dao.LockedContext} and the parent entity needs to be
 * available to observers for processing (e.g. copying annotated fields from parent to child
 * before persistence).
 * <p>
 * Consumers can register an observer that visits this OpContext and applies custom logic
 * (such as field copying) using the parent reference before the actual save is executed.
 *
 * @param <T> Type of child entity to be saved.
 * @param <R> Return type of the operation after performing any afterSave method.
 * @param <P> Type of the parent entity (from LockedContext).
 */
@Data
@Builder
public class CopyFromParentAndSave<T, R, P> extends OpContext<R> {

  @NonNull
  private T entity;
  @NonNull
  private UnaryOperator<T> saver;
  @Builder.Default
  private Function<T, R> afterSave = t -> (R) t;
  @NonNull
  private P parent;

  @Override
  public R apply(Session session) {
    T result = saver.apply(entity);
    return afterSave.apply(result);
  }

  @Override
  public OpType getOpType() {
    return OpType.COPY_FROM_PARENT_AND_SAVE;
  }

  @Override
  public <R1> R1 visit(OpContextVisitor<R1> visitor) {
    return visitor.visit(this);
  }
}

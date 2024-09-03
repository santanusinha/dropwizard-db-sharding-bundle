package io.appform.dropwizard.sharding.dao.operations;

import java.util.function.Function;
import java.util.function.UnaryOperator;
import lombok.Builder;
import lombok.Data;
import lombok.NonNull;
import org.hibernate.Session;

/**
 * Persists given entity to the DB.
 * Optional afterSave function performed within the same hibernate session.
 *
 * @param <T> Type of entity to be saved.
 * @param <R> Return type of the operation after performing any afterSave method.
 */
@Data
@Builder
public class Save<T, R> extends OpContext<R> {

  @NonNull
  private T entity;
  @NonNull
  private UnaryOperator<T> saver;
  @Builder.Default
  private Function<T, R> afterSave = t -> (R) t;

  @Override
  public R apply(Session session) {
    T result = saver.apply(entity);
    return afterSave.apply(result);
  }

  @Override
  public OpType getOpType() {
    return OpType.SAVE;
  }

  @Override
  public <R1> R1 visit(OpContextVisitor<R1> visitor) {
    return visitor.visit(this);
  }
}

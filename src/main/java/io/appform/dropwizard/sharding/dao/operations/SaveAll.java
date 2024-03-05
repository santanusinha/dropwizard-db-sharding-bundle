package io.appform.dropwizard.sharding.dao.operations;

import java.util.Collection;
import java.util.function.Function;
import lombok.Data;
import lombok.NonNull;
import lombok.Builder;
import org.hibernate.Session;

/**
 * Persists collection of entities to DB.
 *
 * @param <T> Type of entity to be persisted.
 */
@Data
@Builder
public class SaveAll<T> extends OpContext<Boolean> {

  @NonNull
  private Collection<T> entities;
  @NonNull
  private Function<Collection<T>, Boolean> saver;


  @Override
  public Boolean apply(Session session) {
    return saver.apply(entities);
  }

  @Override
  public OpType getOpType() {
    return OpType.SAVE_ALL;
  }

  @Override
  public <R> R visit(OpContextVisitor<R> visitor) {
    return visitor.visit(this);
  }
}

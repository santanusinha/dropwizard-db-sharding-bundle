package io.appform.dropwizard.sharding.dao.operations.lookupdao;

import io.appform.dropwizard.sharding.dao.operations.OpContext;
import io.appform.dropwizard.sharding.dao.operations.OpType;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Function;
import lombok.Data;
import lombok.NonNull;
import lombok.Builder;
import org.hibernate.Session;

/**
 * Get an entity from DB by lookup key, mutate it and persist it back to DB. All in same hibernate
 * session.
 *
 * @param <T> Type of entity to get and update.
 */
@Data
@Builder
public class GetAndUpdateByLookupKey<T> extends OpContext<Boolean> {

  @NonNull
  private String id;
  @NonNull
  private Function<String, T> getter;
  @NonNull
  private Function<Optional<T>, T> mutator;
  @NonNull
  private Consumer<T> updater;

  @Override
  public Boolean apply(Session session) {
    T entity = getter.apply(id);
    T newEntity = mutator.apply(Optional.ofNullable(entity));
    if (null == newEntity) {
      return false;
    }
    updater.accept(entity);
    return true;
  }

  @Override
  public OpType getOpType() {
    return OpType.GET_AND_UPDATE_BY_LOOKUP_KEY;
  }

  @Override
  public <R> R visit(OpContextVisitor<R> visitor) {
    return visitor.visit(this);
  }
}

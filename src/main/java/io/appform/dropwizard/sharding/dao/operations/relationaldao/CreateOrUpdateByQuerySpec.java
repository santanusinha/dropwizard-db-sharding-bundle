package io.appform.dropwizard.sharding.dao.operations.relationaldao;

import io.appform.dropwizard.sharding.dao.operations.OpContext;
import io.appform.dropwizard.sharding.dao.operations.OpType;
import io.appform.dropwizard.sharding.query.QuerySpec;
import lombok.Builder;
import lombok.Data;
import lombok.NonNull;
import lombok.val;
import org.hibernate.Session;

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
public class CreateOrUpdateByQuerySpec<U, T> extends OpContext<T> {

  @NonNull
  QuerySpec<U, U> criteria;
  UnaryOperator<T> mutator;
  Supplier<T> entityGenerator;
  private Function<QuerySpec<U, U>, T> getLockedForWrite;
  private Function<QuerySpec<U, U>, T> getter;
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
    return OpType.CREATE_OR_UPDATE_BY_QUERY_SPEC;
  }

  @Override
  public <R> R visit(OpContextVisitor<R> visitor) {
    return visitor.visit(this);
  }
}

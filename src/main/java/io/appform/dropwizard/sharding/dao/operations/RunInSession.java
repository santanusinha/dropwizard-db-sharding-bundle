package io.appform.dropwizard.sharding.dao.operations;

import lombok.Builder;
import lombok.Data;
import lombok.NonNull;
import org.hibernate.Session;

import java.util.function.Function;

;

/**
 * Run any action within a session on specific shard.
 *
 * @param <T> return type of the given action.
 */
@Data
@Builder
public class RunInSession<T> extends OpContext<T> {

  @NonNull
  private Function<Session, T> handler;

  @Override
  public T apply(Session session) {
    return handler.apply(session);
  }

  @Override
  public OpType getOpType() {
    return OpType.RUN_IN_SESSION;
  }

  @Override
  public <R> R visit(OpContextVisitor<R> visitor) {
    return visitor.visit(this);
  }
}

package io.appform.dropwizard.sharding.dao.operations;

import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NonNull;
import lombok.ToString;

import java.util.function.Function;
import java.util.function.UnaryOperator;

/**
 * A {@link Save} of a child entity performed within a {@link io.appform.dropwizard.sharding.dao.LockedContext},
 * which additionally carries a typed reference to the locked parent entity.
 * <p>
 * Being a {@link Save}, it behaves exactly like one ({@code entity}, {@code saver}, {@code afterSave} and
 * {@code apply} are inherited unchanged); the only addition is {@code getParent()}. Observers can use the parent to
 * apply custom logic (e.g. copying annotated fields from parent to child) before the actual save is executed.
 * <p>
 * Visitors that do not override {@link OpContextVisitor#visit(SaveWithParent)} receive this very instance through
 * {@link OpContextVisitor#visit(Save)}, so whatever they do to it (wrap/replace the saver, replace afterSave, replace
 * the entity, keep the reference) is what gets executed.
 * <p>
 * Build instances with {@code saveWithParentBuilder()}. Note that {@code SaveWithParent.builder()} resolves to the
 * inherited {@code Save.builder()} and builds a plain {@link Save}.
 *
 * @param <T> Type of child entity to be saved.
 * @param <R> Return type of the operation after performing any afterSave method.
 * @param <P> Type of the parent entity (from LockedContext).
 */
@Getter
@ToString(callSuper = true)
@EqualsAndHashCode(callSuper = true)
public class SaveWithParent<T, R, P> extends Save<T, R> {

  @NonNull
  private final P parent;

  /**
   * @param entity    child entity to save; must not be null (enforced by {@link Save}'s constructor).
   * @param saver     function that persists the entity; must not be null (enforced by {@link Save}'s constructor).
   * @param afterSave function applied to the saved entity; when not provided (null), the saved entity itself is
   *                  returned, same as {@link Save}'s default.
   * @param parent    parent entity held by the LockedContext; must not be null.
   */
  @Builder(builderMethodName = "saveWithParentBuilder")
  private SaveWithParent(T entity,
                         UnaryOperator<T> saver,
                         Function<T, R> afterSave,
                         @NonNull P parent) {
    super(entity, saver, afterSave != null ? afterSave : identity());
    this.parent = parent;
  }

  @Override
  public OpType getOpType() {
    return OpType.SAVE_WITH_PARENT;
  }

  @Override
  public <R1> R1 visit(OpContextVisitor<R1> visitor) {
    return visitor.visit(this);
  }

  @SuppressWarnings("unchecked")
  private static <T, R> Function<T, R> identity() {
    return t -> (R) t;
  }
}

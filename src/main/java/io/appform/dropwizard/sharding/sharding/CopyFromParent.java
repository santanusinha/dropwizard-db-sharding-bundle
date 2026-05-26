package io.appform.dropwizard.sharding.sharding;

import java.lang.annotation.*;

/**
 * Marks a field on a child entity whose value should be automatically
 * copied from the corresponding field on the parent entity before persist.
 *
 * Requires @ParentEntity on the enclosing class.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.FIELD)
public @interface CopyFromParent {
    /** Name of the field on the parent entity to copy from. */
    String field();

    /**
     * Whether to override an existing non-default value on the child field.
     * <p>
     * When {@code true} (default), the parent value is always copied to the child field,
     * regardless of the child field's current value.
     * <p>
     * When {@code false}, the parent value is copied only if the child field is currently
     * at its default value ({@code null} for reference types, {@code 0}/{@code 0L}/{@code 0.0}
     * for numeric primitives, {@code false} for boolean, {@code '\0'} for char).
     * <p>
     * <b>Note:</b> For primitive fields, the default value (e.g. {@code 0} for int) is
     * indistinguishable from an intentionally set value. Use wrapper types (e.g. {@code Integer})
     * if you need reliable "unset" detection with {@code override = false}.
     */
    boolean override() default true;
}
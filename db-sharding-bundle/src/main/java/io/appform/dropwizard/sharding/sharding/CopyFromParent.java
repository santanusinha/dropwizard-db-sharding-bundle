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
     * When {@code false}, the parent value is not copied to the child field.
     */
    boolean override() default true;
}
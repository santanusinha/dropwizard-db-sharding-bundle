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
}
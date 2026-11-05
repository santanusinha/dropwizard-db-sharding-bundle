package io.appform.dropwizard.sharding.sharding;

import java.lang.annotation.*;

/**
 * Declares the parent entity type for a child entity.
 * Used in conjunction with @DerivedFromParent for automatic field propagation.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface ParentEntity {
    Class<?> value();
}
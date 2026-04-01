package io.appform.dropwizard.sharding.sharding;

import java.lang.annotation.Retention;
import java.lang.annotation.Target;

import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

/**
 * Used to mark a transactional operation in a derived {@link io.appform.dropwizard.sharding.dao.AbstractDAO}
 * for use by {@link io.appform.dropwizard.sharding.dao.WrapperDao}
 */
@Target(METHOD)
@Retention(RUNTIME)
public @interface ShardedTransaction {
    String DEFAULT_NAME = "hibernate";

    String value() default DEFAULT_NAME;

    boolean readOnly() default false;
}

package io.appform.dropwizard.sharding.sharding;

import io.dropwizard.hibernate.HibernateBundle;

import java.lang.annotation.Retention;
import java.lang.annotation.Target;

import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

/**
 * Used to mark a transactional operation in a derviced {@link io.dropwizard.hibernate.AbstractDAO}
 * for use by {@link io.appform.dropwizard.sharding.dao.WrapperDao}
 */
@Target(METHOD)
@Retention(RUNTIME)
public @interface ShardedTransaction {
    String value() default HibernateBundle.DEFAULT_NAME;

    boolean readOnly() default false;
}

package io.appform.dropwizard.sharding.sharding;

import io.appform.dropwizard.sharding.hibernate.SessionFactoryFactory;

import java.lang.annotation.Retention;
import java.lang.annotation.Target;

import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

/**
 * Used to mark a transactional operation in a derviced {@link io.appform.dropwizard.sharding.dao.AbstractDAO}
 * for use by {@link io.appform.dropwizard.sharding.dao.WrapperDao}
 */
@Target(METHOD)
@Retention(RUNTIME)
public @interface ShardedTransaction {
    String value() default SessionFactoryFactory.DEFAULT_NAME;

    boolean readOnly() default false;
}

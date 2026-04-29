package io.appform.dropwizard.sharding.sharding;

import io.appform.dropwizard.sharding.dao.LookupDao;

import java.lang.annotation.Retention;
import java.lang.annotation.Target;

import static java.lang.annotation.ElementType.FIELD;
import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

/**
 * Annotation to annotate top level lookup key to be used by {@link LookupDao}
 */
@Target({FIELD, METHOD})
@Retention(RUNTIME)
public @interface LookupKey {
}

package io.appform.dropwizard.sharding.sharding;

import java.lang.annotation.Retention;
import java.lang.annotation.Target;

import static java.lang.annotation.ElementType.FIELD;
import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

/**
 * Annotation to annotate bucketKey
 */
@Target({FIELD, METHOD})
@Retention(RUNTIME)
public @interface BucketKey {
}
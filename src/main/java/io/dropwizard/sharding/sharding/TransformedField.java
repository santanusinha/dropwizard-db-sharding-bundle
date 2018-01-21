package io.dropwizard.sharding.sharding;

import java.lang.annotation.Retention;
import java.lang.annotation.Target;

import static java.lang.annotation.ElementType.FIELD;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

/**
 * @author tushar.naik
 * @version 1.0  14/11/17 - 10:48 PM
 */
@Target(FIELD)
@Retention(RUNTIME)
public @interface TransformedField {
}

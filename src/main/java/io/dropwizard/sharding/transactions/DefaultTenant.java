package io.dropwizard.sharding.transactions;

import java.lang.annotation.*;

/**
 * Created on 03/10/18
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface DefaultTenant {
}

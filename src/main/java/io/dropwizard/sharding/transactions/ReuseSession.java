package io.dropwizard.sharding.transactions;


import java.lang.annotation.*;

/**
 * Created by simarpreet on 19/07/17.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface ReuseSession {

}


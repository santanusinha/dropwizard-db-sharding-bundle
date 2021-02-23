package io.appform.dropwizard.sharding.healthcheck;

import com.codahale.metrics.health.HealthCheck;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * @author - suraj.s
 * @version - 1.0
 * @since - 23-02-2021
 */
public class ApplicationWarmUpHealthCheck extends HealthCheck {

    private static final String DB_HEALTHY_MESSAGE = "Database is successfully warmed up.";
    private static final String DB_UNHEALTHY_MESSAGE = "Database is still warming up.";

    private final AtomicBoolean applicationWarmedUpFlag;

    public ApplicationWarmUpHealthCheck(final AtomicBoolean applicationWarmedUpFlag) {
        this.applicationWarmedUpFlag = applicationWarmedUpFlag;
    }

    @Override
    protected Result check() throws Exception {
        if (applicationWarmedUpFlag.get()) {
            return Result.healthy(DB_HEALTHY_MESSAGE);
        }

        return Result.unhealthy(DB_UNHEALTHY_MESSAGE);
    }
}

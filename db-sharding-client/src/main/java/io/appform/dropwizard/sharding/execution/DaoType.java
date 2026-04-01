package io.appform.dropwizard.sharding.execution;

import lombok.Getter;

public enum DaoType {

    RELATIONAL("io.appform.dropwizard.sharding.dao.RelationalDao"),
    LOOKUP("io.appform.dropwizard.sharding.dao.LookupDao");

    @Getter
    private final String metricName;

    DaoType(final String metricName) {
        this.metricName = metricName;
    }
}

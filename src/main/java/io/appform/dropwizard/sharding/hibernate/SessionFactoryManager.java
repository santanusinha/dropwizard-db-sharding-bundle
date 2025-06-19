package io.appform.dropwizard.sharding.hibernate;

import io.dropwizard.lifecycle.Managed;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;

@Slf4j
public class SessionFactoryManager implements Managed {

    private final List<SessionFactorySource> sessionFactorySources = new ArrayList<>();

    public SessionFactoryManager(final List<SessionFactorySource> sources) {
        sessionFactorySources.addAll(sources);
    }

    @Override
    public void start() throws Exception {
            sessionFactorySources.forEach(sessionFactorySource -> {
                try {
                    sessionFactorySource.getDataSource().start();
                } catch (Exception e) {
                    log.error("Error starting datasource", e);
                    throw new RuntimeException(e);
                }
            });
    }

    @Override
    public void stop() throws Exception {
        for (SessionFactorySource sessionFactorySource : sessionFactorySources) {
            sessionFactorySource.getFactory().close();
            sessionFactorySource.getDataSource().stop();
        }
    }

}

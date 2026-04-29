package io.appform.dropwizard.sharding.hibernate;

import io.dropwizard.db.ManagedDataSource;
import lombok.Builder;
import lombok.Getter;
import org.hibernate.SessionFactory;

@Builder
@Getter
public class SessionFactorySource {
    private final SessionFactory factory;
    private final ManagedDataSource dataSource;
}

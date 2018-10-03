package io.dropwizard.sharding.testdata.dao;

import io.dropwizard.hibernate.AbstractDAO;
import io.dropwizard.sharding.testdata.entities.CustomerToBucketMapping;
import org.hibernate.Criteria;
import org.hibernate.SessionFactory;
import org.hibernate.criterion.Restrictions;

import javax.inject.Inject;
import javax.inject.Named;
import java.util.Optional;

/**
 * Created on 03/10/18
 */
public class CustomerToBucketMappingDAO extends AbstractDAO<CustomerToBucketMapping> {

    private static final String CUSTOMER_ID = "customerId";

    /**
     * Creates a new DAO with a given session provider.
     *
     * @param sessionFactory a session provider
     */
    @Inject
    public CustomerToBucketMappingDAO(@Named("session") SessionFactory sessionFactory) {
        super(sessionFactory);
    }

    // TODO : Replace with criteriaBuilder()
    public Optional<String> getBucketId(String customerId) {
        Criteria criteria = criteria()
                .add(Restrictions.eq(CUSTOMER_ID, customerId));
        CustomerToBucketMapping mapping = (CustomerToBucketMapping) criteria.uniqueResult();
        return Optional.ofNullable(mapping).map(CustomerToBucketMapping::getBucketId);
    }
}

package in.cleartax.dropwizard.sharding.dao;

import in.cleartax.dropwizard.sharding.entities.Customer;
import io.dropwizard.hibernate.AbstractDAO;
import org.hibernate.Criteria;
import org.hibernate.SessionFactory;
import org.hibernate.criterion.Restrictions;

import javax.inject.Inject;
import javax.inject.Named;

/**
 * Created on 02/11/18
 */
public class CustomerDao extends AbstractDAO<Customer> {
    /**
     * Creates a new DAO with a given session provider.
     *
     * @param sessionFactory a session provider
     */
    @Inject
    public CustomerDao(@Named("session") SessionFactory sessionFactory) {
        super(sessionFactory);
    }

    public Customer getByUserName(String userName) {
        Criteria criteria = criteria().add(Restrictions.eq("userName", userName));
        return (Customer) criteria.uniqueResult();
    }
}

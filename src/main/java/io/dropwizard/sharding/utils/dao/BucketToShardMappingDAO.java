package io.dropwizard.sharding.utils.dao;

import io.dropwizard.hibernate.AbstractDAO;
import io.dropwizard.sharding.utils.entities.BucketToShardMapping;
import org.hibernate.Criteria;
import org.hibernate.SessionFactory;
import org.hibernate.criterion.Restrictions;

import javax.inject.Inject;
import javax.inject.Named;
import java.util.Optional;

/**
 * Created on 03/10/18
 */
public class BucketToShardMappingDAO extends AbstractDAO<BucketToShardMapping> {

    private static final String BUCKET_ID = "bucketId";

    @Inject
    public BucketToShardMappingDAO(@Named("session") SessionFactory sessionFactory) {
        super(sessionFactory);
    }

    // TODO : Replace with CriteriaBuilder
    public Optional<String> getShardId(String bucketId) {
        Criteria criteria = criteria()
                .add(Restrictions.eq(BUCKET_ID, bucketId));
        BucketToShardMapping mapping = (BucketToShardMapping) criteria.uniqueResult();
        return Optional.ofNullable(mapping).map(BucketToShardMapping::getShardId);
    }
}

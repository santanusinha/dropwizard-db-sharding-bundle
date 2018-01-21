package io.dropwizard.sharding.dao;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.Lists;
import io.dropwizard.sharding.dao.testdata.ModelHelper;
import io.dropwizard.sharding.dao.testdata.transformation.Identity;
import io.dropwizard.sharding.dao.testdata.transformation.StateCensus;
import io.dropwizard.sharding.sharding.ShardManager;
import io.dropwizard.sharding.sharding.impl.ConsistentHashBucketIdExtractor;
import io.dropwizard.sharding.transformer.impl.Base64ByteTransformer;
import org.hibernate.SessionFactory;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import java.util.List;
import java.util.Optional;

import static io.dropwizard.sharding.dao.DaoSessionHelper.buildSessionFactory;

/**
 * @author tushar.naik
 * @version 1.0  20/01/18 - 8:32 PM
 */
public class TransformedLookupDaoTest {

    private List<SessionFactory> sessionFactories = Lists.newArrayList();
    private TransformedLookupDao<StateCensus, Identity, byte[], String> lookupDao;

    @Before
    public void setUp() throws Exception {

        for (int i = 0; i < 8; i++) {
            sessionFactories.add(buildSessionFactory(String.format("db_%d", i)));
        }
        ShardManager shardManager = new ShardManager(sessionFactories.size());
        ObjectMapper mapper = new ObjectMapper();
        lookupDao = new TransformedLookupDao<>(sessionFactories,
                                               StateCensus.class,
                                               shardManager,
                                               new ConsistentHashBucketIdExtractor<>(),
                                               new Base64ByteTransformer<>(Identity.class, mapper));
    }

    @After
    public void tearDown() throws Exception {
        for (SessionFactory sessionFactory : sessionFactories) {
            sessionFactory.close();
        }
    }

    @Test
    public void testDbTransformedSaveAndGet() throws Exception {
        StateCensus entity = ModelHelper.sampleStateCensus();

        /* base tests */
        Assert.assertFalse(lookupDao.get(entity.getSsn()).isPresent());

        /* save data */
        Optional<StateCensus> save = lookupDao.save(entity);

        /* check if data is saved and transformation was performed */
        Assert.assertTrue(save.isPresent());
        Assert.assertNotNull(save.get().getTransformedData());

        /* get the same lookup key */
        Optional<StateCensus> k1 = lookupDao.get(entity.getSsn());

        /* check if data is intact after retrieving */
        Assert.assertTrue(k1.isPresent());
        Assert.assertNotNull(k1.get().getTransformedData());
        Assert.assertEquals(entity.getIdentity().getName(), k1.get().getIdentity().getName());
        Assert.assertEquals(entity.getIdentity().getAddress().getLocation(), k1.get().getIdentity().getAddress().getLocation());

        /* check if transformed is working fine (this shouldn't be calling the transformation module) */
        Optional<StateCensus> k2 = lookupDao.getTransformed(entity.getSsn());
        Assert.assertNull(k2.get().getIdentity());
        Assert.assertNotNull(k2.get().getTransformedData());
    }

    @Test
    public void testDbTransformUpdate() throws Exception {
        StateCensus entity = ModelHelper.sampleStateCensus();

        /* base tests */
        Assert.assertFalse(lookupDao.get(entity.getSsn()).isPresent());

        /* save data */
        Optional<StateCensus> save = lookupDao.save(entity);

        /* check if data is saved and transformation was performed */
        Assert.assertTrue(save.isPresent());
        Assert.assertTrue(save.get().isActive());
        Assert.assertNotNull(save.get().getTransformedData());

        lookupDao.update(entity.getSsn(), storedEKycData -> {
            if (storedEKycData.isPresent()) {
                storedEKycData.get().setActive(false);
                System.out.println("storedEKycData.get().getIdentity() = " + storedEKycData.get().getIdentity());
                return storedEKycData.get();
            }
            return null;
        });
        Assert.assertFalse(lookupDao.get(entity.getSsn()).get().isActive());
    }
}
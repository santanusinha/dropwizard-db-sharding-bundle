package io.dropwizard.sharding.dao;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.Lists;
import io.dropwizard.sharding.dao.testdata.transformation.Identity;
import io.dropwizard.sharding.dao.testdata.transformation.StateCensus;
import io.dropwizard.sharding.sharding.ShardManager;
import io.dropwizard.sharding.sharding.impl.ConsistentHashBucketIdExtractor;
import io.dropwizard.sharding.transformer.impl.Base64ByteTransformer;
import org.hibernate.SessionFactory;
import org.hibernate.criterion.DetachedCriteria;
import org.hibernate.criterion.Restrictions;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import java.util.List;
import java.util.Optional;

import static io.dropwizard.sharding.dao.DaoSessionHelper.buildSessionFactory;
import static io.dropwizard.sharding.dao.testdata.ModelHelper.sampleStateCensus;

/**
 * @author tushar.naik
 * @version 1.0  20/01/18 - 7:55 PM
 */
public class TransformedRelationalDaoTest {

    private List<SessionFactory> sessionFactories = Lists.newArrayList();
    private TransformedRelationalDao<StateCensus, Identity, byte[], String> relationalDao;

    @Before
    public void setUp() throws Exception {
        for (int i = 0; i < 8; i++) {
            sessionFactories.add(buildSessionFactory(String.format("db_%d", i)));
        }
        ShardManager shardManager = new ShardManager(sessionFactories.size());
        ObjectMapper mapper = new ObjectMapper();
        relationalDao = new TransformedRelationalDao<>(sessionFactories,
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
        StateCensus entity = sampleStateCensus();

        /* save data */
        Optional<StateCensus> save = relationalDao.save(entity.getStateName(), entity);

        /* check if data is saved and transformation was performed */
        Assert.assertTrue(save.isPresent());
        Assert.assertNotNull(save.get().getTransformedData());

        /* select on the same key */
        List<StateCensus> k1 = relationalDao.select(entity.getStateName(),
                                                    DetachedCriteria.forClass(StateCensus.class)
                                                                    .add(Restrictions.eq("ssn", entity.getSsn()))
                                                                    .add(Restrictions.eq("stateName", entity.getStateName())));

        /* check if data is intact after retrieving */
        Assert.assertEquals(1, k1.size());
        Assert.assertNotNull(k1.get(0).getTransformedData());
        Assert.assertNotNull(k1.get(0).getIdentity());
        Assert.assertEquals(entity.getIdentity().getName(), k1.get(0).getIdentity().getName());
        Assert.assertEquals(entity.getIdentity().getAddress().getLocation(), k1.get(0).getIdentity().getAddress().getLocation());

        /* get the same lookup key */
        List<StateCensus> k2 = relationalDao
                .select(entity.getStateName(), DetachedCriteria.forClass(StateCensus.class)
                                                               .add(Restrictions.eq("ssn", entity.getSsn()))
                                                               .add(Restrictions.eq("stateName", entity.getStateName())),

                        0, 10);

        /* check if data is intact after retrieving */
        Assert.assertFalse(k2.isEmpty());
        Assert.assertEquals(1, k2.size());
        Assert.assertNotNull(k2.get(0).getTransformedData());


        StateCensus entity2 = sampleStateCensus("ssn2");

        /* save data */
        Optional<StateCensus> save2 = relationalDao.save(entity2.getStateName(), entity2);

        /* select on the same key */
        List<StateCensus> stateCensusList = relationalDao.select(entity.getStateName(),
                                                                 DetachedCriteria.forClass(StateCensus.class)
                                                                                 .add(Restrictions.eq("stateName",
                                                                                                      entity.getStateName())));
        Assert.assertEquals(2, stateCensusList.size());
        Assert.assertNotNull(stateCensusList.get(0).getTransformedData());
        Assert.assertNotNull(stateCensusList.get(0).getIdentity());
        Assert.assertNotNull(stateCensusList.get(1).getTransformedData());
        Assert.assertNotNull(stateCensusList.get(1).getIdentity());
        Assert.assertEquals(entity2.getIdentity().getName(), stateCensusList.get(1).getIdentity().getName());
        Assert.assertEquals(entity2.getIdentity().getAddress().getLocation(), stateCensusList.get(1).getIdentity().getAddress().getLocation());

    }

    @Test
    public void testDbTransformedUpdate() throws Exception {

        StateCensus entity = sampleStateCensus();

        /* save data */
        Optional<StateCensus> save = relationalDao.save(entity.getStateName(), entity);

        /* check if data is saved and transformation was performed */
        Assert.assertTrue(save.isPresent());
        Assert.assertNotNull(save.get().getTransformedData());

        /* select on the same key */
        List<StateCensus> k1 = relationalDao.select(entity.getStateName(),
                                                    DetachedCriteria.forClass(StateCensus.class)
                                                                    .add(Restrictions.eq("ssn", entity.getSsn()))
                                                                    .add(Restrictions.eq("stateName", entity.getStateName())));

        /* check if data is intact after retrieving */
        Assert.assertEquals(1, k1.size());
        Assert.assertNotNull(k1.get(0).getTransformedData());
        Assert.assertNotNull(k1.get(0).getIdentity());
        Assert.assertEquals(entity.getIdentity().getName(), k1.get(0).getIdentity().getName());
        Assert.assertEquals(entity.getIdentity().getAddress().getLocation(), k1.get(0).getIdentity().getAddress().getLocation());

        relationalDao.update(entity.getStateName(), DetachedCriteria.forClass(StateCensus.class)
                                                                    .add(Restrictions.eq("ssn", entity.getSsn()))
                                                                    .add(Restrictions.eq("stateName", entity.getStateName())),
                             k -> {
                                 k.setActive(false);
                                 return k;
                             });


        k1 = relationalDao.select(entity.getStateName(), DetachedCriteria.forClass(StateCensus.class)
                                                                         .add(Restrictions.eq("ssn", entity.getSsn()))
                                                                         .add(Restrictions.eq("stateName", entity.getStateName())));

        /* check if data is intact after retrieve */
        Assert.assertEquals(1, k1.size());
        Assert.assertNotNull(k1.get(0).getTransformedData());
        Assert.assertNotNull(k1.get(0).getIdentity());
        Assert.assertEquals(entity.getIdentity().getName(), k1.get(0).getIdentity().getName());
        Assert.assertEquals(entity.getIdentity().getAddress().getLocation(), k1.get(0).getIdentity().getAddress().getLocation());
        Assert.assertEquals(false, k1.get(0).isActive());

    }

}
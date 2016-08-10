/*
 * Copyright 2016 Santanu Sinha <santanu.sinha@gmail.com>
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

package io.dropwizard.sharding.dao;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import io.dropwizard.sharding.dao.testdata.entities.TestEntity;
import io.dropwizard.sharding.dao.testdata.entities.Audit;
import io.dropwizard.sharding.dao.testdata.entities.Phone;
import io.dropwizard.sharding.dao.testdata.entities.Transaction;
import io.dropwizard.sharding.sharding.ShardManager;
import io.dropwizard.sharding.sharding.impl.EncodedBucketIdExtractor;
import org.hibernate.SessionFactory;
import org.hibernate.boot.registry.StandardServiceRegistry;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
import org.hibernate.cfg.Configuration;
import org.hibernate.criterion.DetachedCriteria;
import org.hibernate.criterion.Restrictions;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.Assert.*;

public class LookupDaoTest {

    private List<SessionFactory> sessionFactories = Lists.newArrayList();
    private LookupDao<TestEntity> lookupDao;
    private LookupDao<Phone> phoneDao;
    private RelationalDao<Transaction> transactionDao;
    private RelationalDao<Audit> auditDao;
    private ShardManager shardManager;

    private SessionFactory buildSessionFactory(String dbName) {
        Configuration configuration = new Configuration();
        configuration.setProperty("hibernate.dialect",
                "org.hibernate.dialect.H2Dialect");
        configuration.setProperty("hibernate.connection.driver_class",
                "org.h2.Driver");
        configuration.setProperty("hibernate.connection.url", "jdbc:h2:mem:" + dbName);
        configuration.setProperty("hibernate.hbm2ddl.auto", "create");
        configuration.setProperty("hibernate.current_session_context_class", "managed");
        configuration.addAnnotatedClass(TestEntity.class);
        configuration.addAnnotatedClass(Phone.class);
        configuration.addAnnotatedClass(Transaction.class);
        configuration.addAnnotatedClass(Audit.class);

        StandardServiceRegistry serviceRegistry
                = new StandardServiceRegistryBuilder().applySettings(
                            configuration.getProperties()).build();
        return configuration.buildSessionFactory(serviceRegistry);
    }

    @Before
    public void before() {
        for (int i = 0; i < 2; i++) {
            sessionFactories.add(buildSessionFactory(String.format("db_%d", i)));
        }
        shardManager = new ShardManager(sessionFactories.size());
        phoneDao = new LookupDao<>(sessionFactories, Phone.class, shardManager);
        transactionDao = new RelationalDao<>(sessionFactories, Transaction.class, shardManager);
        auditDao = new RelationalDao<>(sessionFactories, Audit.class, shardManager);
    }

    @After
    public void after() {
        sessionFactories.forEach(SessionFactory::close);
    }

    @Test
    public void testSave() throws Exception {
        lookupDao = new LookupDao<>(sessionFactories, TestEntity.class, shardManager);
        TestEntity testEntity = TestEntity.builder()
                                    .externalId("testId")
                                    .text("Some Text")
                                    .build();
        lookupDao.save(testEntity);

        assertEquals(true, lookupDao.exists("testId"));
        assertEquals(false, lookupDao.exists("testId1"));
        Optional<TestEntity> result = lookupDao.get("testId");
        assertEquals("Some Text", result.get().getText());

        testEntity.setText("Some New Text");
        lookupDao.save(testEntity);
        result = lookupDao.get("testId");
        assertEquals("Some New Text", result.get().getText());

        boolean updateStatus = lookupDao.update("testId", entity -> {
            if(entity.isPresent()) {
                TestEntity e = entity.get();
                e.setText("Updated text");
                return e;
            }
            return null;
        });

        assertTrue(updateStatus);
        result = lookupDao.get("testId");
        assertEquals("Updated text", result.get().getText());

        updateStatus = lookupDao.update("testIdxxx", entity -> {
            if(entity.isPresent()) {
                TestEntity e = entity.get();
                e.setText("Updated text");
                return e;
            }
            return null;
        });

        assertFalse(updateStatus);
    }

    @Test
    public void testSave_withKeyEncodedShardId() throws Exception {
        lookupDao = new LookupDao<>(sessionFactories, TestEntity.class, shardManager, new EncodedBucketIdExtractor(4,3));
        TestEntity testEntity = TestEntity.builder()
                .externalId("shar001")
                .text("Some Text")
                .build();
        lookupDao.save(testEntity);

        assertEquals(true, lookupDao.exists("shar001"));
        assertEquals(false, lookupDao.exists("shar002"));
        Optional<TestEntity> result = lookupDao.get("shar001");
        assertEquals("Some Text", result.get().getText());

        testEntity.setText("Some New Text");
        lookupDao.save(testEntity);
        result = lookupDao.get("shar001");
        assertEquals("Some New Text", result.get().getText());

        boolean updateStatus = lookupDao.update("shar001", entity -> {
            if(entity.isPresent()) {
                TestEntity e = entity.get();
                e.setText("Updated text");
                return e;
            }
            return null;
        });

        assertTrue(updateStatus);
        result = lookupDao.get("shar001");
        assertEquals("Updated text", result.get().getText());

        updateStatus = lookupDao.update("shar999", entity -> {
            if(entity.isPresent()) {
                TestEntity e = entity.get();
                e.setText("Updated text");
                return e;
            }
            return null;
        });

        assertFalse(updateStatus);
    }


    @Test
    public void testScatterGather() throws Exception {
        lookupDao = new LookupDao<>(sessionFactories, TestEntity.class, shardManager);
        List<TestEntity> results = lookupDao.scatterGather(DetachedCriteria.forClass(TestEntity.class)
                .add(Restrictions.eq("externalId", "testId")));
        assertTrue(results.isEmpty());

        TestEntity testEntity = TestEntity.builder()
                .externalId("testId")
                .text("Some Text")
                .build();
        lookupDao.save(testEntity);
        results = lookupDao.scatterGather(DetachedCriteria.forClass(TestEntity.class)
                .add(Restrictions.eq("externalId", "testId")));
        assertFalse(results.isEmpty());
        assertEquals("Some Text", results.get(0).getText());
    }

    @Test
    public void testSaveInParentBucket() throws Exception {
        lookupDao = new LookupDao<>(sessionFactories, TestEntity.class, shardManager);
        final String phoneNumber = "9830968020";

        Phone phone = Phone.builder()
                            .phone(phoneNumber)
                            .build();

        Phone savedPhone = phoneDao.save(phone).get();

        Transaction transaction = Transaction.builder()
                                    .transactionId("testTxn")
                                    .to("9830703153")
                                    .amount(100)
                                    .phone(savedPhone)
                                    .build();

        transactionDao.save(savedPhone.getPhone(), transaction).get();
        {
            Transaction resultTx = transactionDao.get(phoneNumber, "testTxn").get();
            assertEquals(phoneNumber, resultTx.getPhone().getPhone());
            assertTrue(transactionDao.exists(phoneNumber, "testTxn"));
            assertFalse(transactionDao.exists(phoneNumber, "testTxn1"));
        }
        {
            Optional<Transaction> resultTx = transactionDao.get(phoneNumber, "testTxn1");
            assertFalse(resultTx.isPresent());
        }
        saveAudit(phoneNumber, "testTxn", "Started");
        saveAudit(phoneNumber, "testTxn", "Underway");
        saveAudit(phoneNumber, "testTxn", "Completed");

        assertEquals(3, auditDao.count(phoneNumber, DetachedCriteria.forClass(Audit.class)
                                                        .add(Restrictions.eq("transaction.transactionId", "testTxn"))));

        List<Audit> audits = auditDao.select(phoneNumber, DetachedCriteria.forClass(Audit.class)
                                                        .add(Restrictions.eq("transaction.transactionId", "testTxn")));
        assertEquals("Started", audits.get(0).getText());

    }

    private void saveAudit(String phone, String transaction, String text) throws Exception {
        auditDao.save(phone, Audit.builder()
                                                    .text(text)
                                                    .transaction(Transaction.builder()
                                                                    .transactionId(transaction)
                                                                    .build())
                                                    .build());
    }

    @Test
    public void testHierarchy() throws Exception {
        lookupDao = new LookupDao<>(sessionFactories, TestEntity.class, shardManager);
        final String phoneNumber = "9986032019";
        saveHierarchy(phoneNumber);
        saveHierarchy("9986402019");

        List<Audit> audits = auditDao.select(phoneNumber, DetachedCriteria.forClass(Audit.class)
                .add(Restrictions.eq("transaction.transactionId", "newTxn-" + phoneNumber)));

        assertEquals(2, audits.size());

        List<Audit> allAudits = auditDao.scatterGather(DetachedCriteria.forClass(Audit.class));
        assertEquals(4, allAudits.size());
    }


    private void saveHierarchy(String phone) throws Exception {

        Transaction transaction = Transaction.builder()
                .transactionId("newTxn-" + phone)
                .amount(100)
                .to("9986402019")
                .build();

        Audit started = Audit.builder()
                            .text("Started")
                            .transaction(transaction)
                            .build();

        Audit completed = Audit.builder()
                .text("Completed")
                .transaction(transaction)
                .build();

        transaction.setAudits(ImmutableList.of(started, completed));

        transactionDao.save(phone, transaction);
    }
}
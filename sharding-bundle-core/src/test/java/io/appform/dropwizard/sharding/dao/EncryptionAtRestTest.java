package io.appform.dropwizard.sharding.dao;

import com.google.common.collect.Lists;
import io.appform.dropwizard.sharding.ShardInfoProvider;
import io.appform.dropwizard.sharding.config.ShardingBundleOptions;
import io.appform.dropwizard.sharding.dao.interceptors.TimerObserver;
import io.appform.dropwizard.sharding.dao.listeners.LoggingListener;
import io.appform.dropwizard.sharding.dao.testdata.entities.TestEncryptedEntity;
import io.appform.dropwizard.sharding.observers.internal.ListenerTriggeringObserver;
import io.appform.dropwizard.sharding.sharding.BalancedShardManager;
import io.appform.dropwizard.sharding.sharding.ShardManager;
import io.appform.dropwizard.sharding.sharding.impl.ConsistentHashBucketIdExtractor;
import io.appform.dropwizard.sharding.utils.ShardCalculator;
import lombok.val;
import org.hibernate.SessionFactory;
import org.hibernate.boot.registry.StandardServiceRegistry;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
import org.hibernate.cfg.Configuration;
import org.jasypt.encryption.pbe.StandardPBEStringEncryptor;
import org.jasypt.hibernate5.encryptor.HibernatePBEEncryptorRegistry;
import org.jasypt.iv.StringFixedIvGenerator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class EncryptionAtRestTest {

    private List<SessionFactory> sessionFactories = Lists.newArrayList();
    private LookupDao<TestEncryptedEntity> lookupDao;

    private SessionFactory buildSessionFactory(String dbName) {
        Configuration configuration = new Configuration();
        configuration.setProperty("hibernate.dialect",
                "org.hibernate.dialect.H2Dialect");
        configuration.setProperty("hibernate.connection.driver_class",
                "org.h2.Driver");
        configuration.setProperty("hibernate.connection.url", "jdbc:h2:mem:" + dbName);
        configuration.setProperty("hibernate.hbm2ddl.auto", "create");
        configuration.setProperty("hibernate.current_session_context_class", "managed");
        configuration.setProperty("hibernate.show_sql", "true");
        configuration.setProperty("hibernate.format_sql", "true");
        configuration.addAnnotatedClass(TestEncryptedEntity.class);
        StandardPBEStringEncryptor strongEncryptor = new StandardPBEStringEncryptor();
        HibernatePBEEncryptorRegistry encryptorRegistry = HibernatePBEEncryptorRegistry.getInstance();
        strongEncryptor.setAlgorithm("PBEWithHmacSHA256AndAES_256");
        strongEncryptor.setPassword("eBhjVFN5LtP6hpwzWdjSkBQg");
        strongEncryptor.setIvGenerator(new StringFixedIvGenerator("8SCaDgvH5xMD3KFE"));
        encryptorRegistry.registerPBEStringEncryptor("encryptedString", strongEncryptor);
        StandardServiceRegistry serviceRegistry
                = new StandardServiceRegistryBuilder().applySettings(
                        configuration.getProperties())
                .build();
        return configuration.buildSessionFactory(serviceRegistry);
    }

    @BeforeEach
    public void before() {
        for (int i = 0; i < 2; i++) {
            sessionFactories.add(buildSessionFactory(String.format("db_%d", i)));
        }
        final ShardManager shardManager = new BalancedShardManager(sessionFactories.size());
        final ShardCalculator<String> shardCalculator = new ShardCalculator<>(shardManager,
                new ConsistentHashBucketIdExtractor<>(
                        shardManager));

        final ShardingBundleOptions shardingOptions= new ShardingBundleOptions();
        final ShardInfoProvider shardInfoProvider = new ShardInfoProvider("default");
        val observer = new TimerObserver(new ListenerTriggeringObserver().addListener(new LoggingListener()));
        lookupDao = new LookupDao<>(sessionFactories, TestEncryptedEntity.class, shardCalculator, shardingOptions,
                shardInfoProvider, observer);
    }

    @AfterEach
    public void after() {
        sessionFactories.forEach(SessionFactory::close);
    }

    @Test
    public void testSave() throws Exception {
        TestEncryptedEntity testEntity = TestEncryptedEntity.builder()
                .externalId("testId")
                .encText("Some Text")
                .build();
        lookupDao.save(testEntity);

        assertEquals(true, lookupDao.exists("testId"));
        assertEquals(false, lookupDao.exists("testId1"));
        Optional<TestEncryptedEntity> result = lookupDao.get("testId");
        assertEquals("Some Text",
                result.get()
                        .getEncText());

        testEntity.setEncText("Some New Text");
        lookupDao.save(testEntity);
        result = lookupDao.get("testId");
        assertEquals("Some New Text",
                result.get()
                        .getEncText());

        boolean updateStatus = lookupDao.update("testId", entity -> {
            if (entity.isPresent()) {
                TestEncryptedEntity e = entity.get();
                e.setEncText("Updated text");
                return e;
            }
            return null;
        });

        assertTrue(updateStatus);
        result = lookupDao.get("testId");
        assertEquals("Updated text",
                result.get()
                        .getEncText());

        updateStatus = lookupDao.update("testIdxxx", entity -> {
            if (entity.isPresent()) {
                TestEncryptedEntity e = entity.get();
                e.setEncText("Updated text");
                return e;
            }
            return null;
        });

        assertFalse(updateStatus);
    }
}

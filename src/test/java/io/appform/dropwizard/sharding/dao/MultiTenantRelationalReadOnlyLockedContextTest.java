package io.appform.dropwizard.sharding.dao;

import com.google.common.collect.Lists;
import com.google.common.collect.Sets;
import io.appform.dropwizard.sharding.ShardInfoProvider;
import io.appform.dropwizard.sharding.config.ShardingBundleOptions;
import io.appform.dropwizard.sharding.dao.interceptors.TimerObserver;
import io.appform.dropwizard.sharding.dao.listeners.LoggingListener;
import io.appform.dropwizard.sharding.observers.internal.ListenerTriggeringObserver;
import io.appform.dropwizard.sharding.sharding.BalancedShardManager;
import io.appform.dropwizard.sharding.sharding.ShardManager;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.SneakyThrows;
import lombok.ToString;
import lombok.val;
import org.hibernate.SessionFactory;
import org.hibernate.boot.registry.StandardServiceRegistry;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
import org.hibernate.cfg.Configuration;
import org.hibernate.criterion.DetachedCriteria;
import org.hibernate.criterion.Restrictions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.GeneratedValue;
import javax.persistence.GenerationType;
import javax.persistence.Id;
import javax.persistence.Table;
import javax.persistence.Transient;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class MultiTenantRelationalReadOnlyLockedContextTest {

    private Map<String, List<SessionFactory>> sessionFactories = new HashMap<>();
    private MultiTenantRelationalDao<Department> departmentRelationalDao;
    private MultiTenantRelationalDao<Ceo> ceoRelationalDao;
    private MultiTenantRelationalDao<Company> companyRelationalDao;

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
        configuration.addAnnotatedClass(Company.class);
        configuration.addAnnotatedClass(Department.class);
        configuration.addAnnotatedClass(Ceo.class);
        StandardServiceRegistry serviceRegistry
                = new StandardServiceRegistryBuilder().applySettings(
                        configuration.getProperties())
                .build();
        return configuration.buildSessionFactory(serviceRegistry);
    }

    @BeforeEach
    public void before() {
        sessionFactories = Map.of("TENANT1",
                List.of(buildSessionFactory("tenant1_1"), buildSessionFactory("tenant1_2")),
                "TENANT2",
                List.of(buildSessionFactory("tenant2_1"), buildSessionFactory("tenant2_2"),
                        buildSessionFactory("tenant2_3"), buildSessionFactory("tenant2_4")));
        Map<String, ShardManager> shardManager = new HashMap<>();
        sessionFactories.forEach((tenant, sessionFactory) ->
                shardManager.put(tenant, new BalancedShardManager(sessionFactory.size())));
        final Map<String, ShardingBundleOptions> shardingOptions = Map.of("TENANT1",
                new ShardingBundleOptions(), "TENANT2", new ShardingBundleOptions());

        final Map<String, ShardInfoProvider> shardInfoProvider = Map.of("TENANT1",
                new ShardInfoProvider("TENANT1"),
                "TENANT2", new ShardInfoProvider("TENANT2"));
        val observer = new TimerObserver(
                new ListenerTriggeringObserver().addListener(new LoggingListener()));

        companyRelationalDao = new MultiTenantRelationalDao<>(sessionFactories, Company.class,
                shardManager, shardingOptions,
                shardInfoProvider, observer);
        departmentRelationalDao = new MultiTenantRelationalDao<>(sessionFactories, Department.class,
                shardManager, shardingOptions,
                shardInfoProvider, observer);
        ceoRelationalDao = new MultiTenantRelationalDao<>(sessionFactories, Ceo.class, shardManager,
                shardingOptions,
                shardInfoProvider, observer);
    }

    @AfterEach
    public void after() {
        sessionFactories.forEach((tenantId, sessionFactory) -> sessionFactory.forEach(
                SessionFactory::close));
    }

    @Test
    @SneakyThrows
    void testRelationalDaoReadOnlyContextWithAssociations() {
        val parentKey = "PARENT_KEY";
        generateData(parentKey);

        val companyId1 = "CMPID1";
        val companyId2 = "CMPID2";

        val parentCriteria = DetachedCriteria.forClass(Company.class)
                .add(Restrictions.in("companyUsageId", Sets.newHashSet(companyId1, companyId2)));

        val associationMappingSpecs = Lists.newArrayList(
                MultiTenantRelationalDao.AssociationMappingSpec.builder().childMappingKey("companyExtId")
                        .parentMappingKey("companyUsageId").build()
        );
        val departmentQueryFilterSpec = MultiTenantRelationalDao.QueryFilterSpec.<Department>builder()
                .associationMappingSpecs(associationMappingSpecs)
                .build();
        val ceoQueryFilterSpec = MultiTenantRelationalDao.QueryFilterSpec.<Ceo>builder()
                .associationMappingSpecs(associationMappingSpecs)
                .build();

        val dataList = companyRelationalDao.readOnlyExecutor("TENANT1", parentKey, parentCriteria, 0, 4)
                .readAugmentParent(departmentRelationalDao, departmentQueryFilterSpec, 0, Integer.MAX_VALUE,
                        Company::setDepartments)
                .readAugmentParent(ceoRelationalDao, ceoQueryFilterSpec, 0, Integer.MAX_VALUE,
                        (parent, childList) -> {
                            parent.setCeo(childList.stream().findAny().orElse(null));
                        })
                .execute()
                .orElse(new ArrayList<>());

        Assertions.assertNotNull(dataList);
        Assertions.assertEquals(2, dataList.size());
        val respCompanyOptionalCase1A = dataList.stream()
                .filter(e -> e.getCompanyUsageId().equals(companyId1))
                .findFirst();
        Assertions.assertTrue(respCompanyOptionalCase1A.isPresent());
        val respCompanyA = respCompanyOptionalCase1A.get();
        Assertions.assertNotNull(respCompanyA.getCeo());
        Assertions.assertEquals(respCompanyA.getCeo().getCompanyExtId(), companyId1);
        Assertions.assertTrue(respCompanyA.getDepartments().stream()
                .allMatch(e -> e.getCompanyExtId().equals(companyId1)));

        val respCompanyOptionalCase1B = dataList.stream()
                .filter(e -> e.getCompanyUsageId().equals(companyId2))
                .findFirst();
        Assertions.assertTrue(respCompanyOptionalCase1B.isPresent());
        val respCompanyB = respCompanyOptionalCase1B.get();
        Assertions.assertNotNull(respCompanyB.getCeo());
        Assertions.assertEquals(respCompanyB.getCeo().getCompanyExtId(), companyId2);
        Assertions.assertTrue(respCompanyB.getDepartments().stream()
                .allMatch(e -> e.getCompanyExtId().equals(companyId2)));
    }

    @Test
    @SneakyThrows
    void testRelationalDaoReadOnlyContextWithCriteria() {
        val parentKey = "PARENT_KEY";
        generateData(parentKey);

        val companyToRetrieve = "CMPID1";

        val parentCriteria = DetachedCriteria.forClass(Company.class)
                .add(Restrictions.eq("companyUsageId", companyToRetrieve));

        val departmentQueryFilterSpec = MultiTenantRelationalDao.QueryFilterSpec.<Department>builder()
                .criteria(DetachedCriteria.forClass(Department.class)
                        .add(Restrictions.eq("companyExtId", companyToRetrieve)))
                .build();

        val ceoQueryFilterSpec = MultiTenantRelationalDao.QueryFilterSpec.<Ceo>builder()
                .criteria(DetachedCriteria.forClass(Ceo.class)
                        .add(Restrictions.eq("companyExtId", companyToRetrieve)))
                .build();

        val dataList = companyRelationalDao.readOnlyExecutor("TENANT1", parentKey, parentCriteria, 0, 4)
                .readAugmentParent(departmentRelationalDao, departmentQueryFilterSpec, 0, Integer.MAX_VALUE,
                        Company::setDepartments)
                .readAugmentParent(ceoRelationalDao, ceoQueryFilterSpec, 0, Integer.MAX_VALUE,
                        (parent, childList) -> {
                            parent.setCeo(childList.stream().findAny().orElse(null));
                        }).execute()
                .orElse(new ArrayList<>());

        Assertions.assertNotNull(dataList);
        Assertions.assertEquals(1, dataList.size());
        val respCompanyOptionalCase2A = dataList.stream()
                .filter(e -> e.getCompanyUsageId().equals(companyToRetrieve))
                .findFirst();
        Assertions.assertTrue(respCompanyOptionalCase2A.isPresent());
        val respCompanyCase2 = respCompanyOptionalCase2A.get();
        Assertions.assertNotNull(respCompanyCase2.getCeo());
        Assertions.assertTrue(respCompanyCase2.getDepartments().stream()
                .allMatch(e -> e.getCompanyExtId().equals(companyToRetrieve)));
    }

    @Test
    @SneakyThrows
    void testRelationalDaoReadOnlyContextWithQuerySpec() {
        val parentKey = "PARENT_KEY";
        generateData(parentKey);

        val companyToRetrieve = "CMPID1";

        val departmentQueryFilterSpec = MultiTenantRelationalDao.QueryFilterSpec.<Department>builder()
                .querySpec((queryRoot, query, criteriaBuilder) -> query.where(
                        criteriaBuilder.equal(queryRoot.get("companyExtId"), companyToRetrieve)))
                .build();

        val ceoQueryFilterSpec = MultiTenantRelationalDao.QueryFilterSpec.<Ceo>builder()
                .querySpec((queryRoot, query, criteriaBuilder) -> query.where(
                        criteriaBuilder.equal(queryRoot.get("companyExtId"), companyToRetrieve)))
                .build();

        val dataList = companyRelationalDao.readOnlyExecutor("TENANT1", parentKey,
                        (queryRoot, query, criteriaBuilder) -> query.where(
                                criteriaBuilder.equal(queryRoot.get("companyUsageId"), companyToRetrieve)), 0, 1)
                .readAugmentParent(departmentRelationalDao, departmentQueryFilterSpec, 0, Integer.MAX_VALUE,
                        Company::setDepartments)
                .readAugmentParent(ceoRelationalDao, ceoQueryFilterSpec, 0, Integer.MAX_VALUE,
                        (parent, childList) -> {
                            parent.setCeo(childList.stream().findAny().orElse(null));
                        }).execute()
                .orElse(new ArrayList<>());

        Assertions.assertNotNull(dataList);
        Assertions.assertEquals(1, dataList.size());
        val respCompanyOptionalCase2A = dataList.stream()
                .filter(e -> e.getCompanyUsageId().equals(companyToRetrieve))
                .findFirst();
        Assertions.assertTrue(respCompanyOptionalCase2A.isPresent());
        val respCompanyCase2 = respCompanyOptionalCase2A.get();
        Assertions.assertNotNull(respCompanyCase2.getCeo());
        Assertions.assertTrue(respCompanyCase2.getDepartments().stream()
                .allMatch(e -> e.getCompanyExtId().equals(companyToRetrieve)));
    }

    private void generateData(String parentKey) {
        Company company1 = Company.builder()
                .companyUsageId("CMPID1")
                .companyId(1l)
                .name("COMP1")
                .build();
        Department eng = Department.builder()
                .name("ENGINEERING")
                .companyExtId(company1.companyUsageId)
                .build();
        Department fin = Department.builder()
                .name("FINANCE")
                .companyExtId(company1.companyUsageId)
                .build();
        Ceo ceo1 = Ceo.builder()
                .companyExtId(company1.companyUsageId)
                .name("KING")
                .build();

        Company company2 = Company.builder()
                .companyId(2l)
                .companyUsageId("CMPID2")
                .name("COMP2")
                .build();
        Department hr = Department.builder()
                .name("HR")
                .companyExtId(company2.companyUsageId)
                .build();
        Ceo ceo2 = Ceo.builder()
                .companyExtId(company2.companyUsageId)
                .name("KING-2")
                .build();

        val lockedContext1 = companyRelationalDao.saveAndGetExecutor("TENANT1", parentKey, company1);
        lockedContext1.save(departmentRelationalDao, eng1 -> eng);
        lockedContext1.save(departmentRelationalDao, fin1 -> fin);
        lockedContext1.save(ceoRelationalDao, ceo -> ceo1);
        lockedContext1.execute();

        val lockedContext2 = companyRelationalDao.saveAndGetExecutor("TENANT1", parentKey, company2);
        lockedContext2.save(departmentRelationalDao, hr1 -> hr);
        lockedContext2.save(ceoRelationalDao, ceo -> ceo2);
        lockedContext2.execute();
    }

    @Entity
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    @Table(name = "company")
    public static class Company {

        @Id
        @Column(name = "company_id", nullable = false, unique = true)
        private long companyId;

        @Column(name = "companyUsageId", nullable = false)
        private String companyUsageId;

        @Column(name = "name", nullable = false)
        private String name;

        @Transient
        private List<Department> departments;

        @Transient
        private Ceo ceo;

    }

    @Entity
    @Table(name = "departments")
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @ToString
    @Builder
    public static class Department {

        @Id
        @GeneratedValue(strategy = GenerationType.IDENTITY)
        @Column(name = "id")
        private long id;

        @Column(name = "name")
        private String name;

        @Column(name = "company_ext_id", nullable = false)
        private String companyExtId;

    }

    @Entity
    @Table(name = "ceo")
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @ToString
    @Builder
    public static class Ceo {

        @Id
        @GeneratedValue(strategy = GenerationType.IDENTITY)
        @Column(name = "id")
        private long id;

        @Column(name = "name")
        private String name;

        @Column(name = "company_ext_id", nullable = false)
        private String companyExtId;

    }
}

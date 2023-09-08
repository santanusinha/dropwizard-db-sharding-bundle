package io.appform.dropwizard.sharding.dao.testdata;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import com.google.common.collect.Sets;
import io.appform.dropwizard.sharding.config.ShardingBundleOptions;
import io.appform.dropwizard.sharding.dao.RelationalDao;
import io.appform.dropwizard.sharding.sharding.BalancedShardManager;
import io.appform.dropwizard.sharding.sharding.ShardManager;
import io.appform.dropwizard.sharding.sharding.impl.ConsistentHashBucketIdExtractor;
import io.appform.dropwizard.sharding.utils.ShardCalculator;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.SneakyThrows;
import lombok.ToString;
import lombok.val;
import org.hibernate.SessionFactory;
import org.hibernate.annotations.Cascade;
import org.hibernate.annotations.CascadeType;
import org.hibernate.boot.registry.StandardServiceRegistry;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
import org.hibernate.cfg.Configuration;
import org.hibernate.criterion.DetachedCriteria;
import org.hibernate.criterion.Restrictions;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.GeneratedValue;
import javax.persistence.GenerationType;
import javax.persistence.Id;
import javax.persistence.JoinColumn;
import javax.persistence.ManyToOne;
import javax.persistence.OneToMany;
import javax.persistence.Table;
import java.util.List;

public class RelationalReadOnlyLockedContextTest {

    private List<SessionFactory> sessionFactories = Lists.newArrayList();
    private RelationalDao<Department> departmentRelationalDao;
    private RelationalDao<Company> companyRelationalDao;

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
        StandardServiceRegistry serviceRegistry
                = new StandardServiceRegistryBuilder().applySettings(
                        configuration.getProperties())
                .build();
        return configuration.buildSessionFactory(serviceRegistry);
    }

    @Before
    public void before() {
        for (int i = 0; i < 2; i++) {
            sessionFactories.add(buildSessionFactory(String.format("db_%d", i)));
        }
        final ShardManager shardManager = new BalancedShardManager(sessionFactories.size());
        final ShardCalculator<String> shardCalculator = new ShardCalculator<>(shardManager,
                new ConsistentHashBucketIdExtractor<>(
                        shardManager));
        final ShardingBundleOptions shardingOptions= new ShardingBundleOptions();
        companyRelationalDao = new RelationalDao<>(sessionFactories, Company.class, shardCalculator);
        departmentRelationalDao = new RelationalDao<>(sessionFactories, Department.class, shardCalculator);
    }
    @After
    public void after() {
        sessionFactories.forEach(SessionFactory::close);
    }

    @Test
    @SneakyThrows
    public void testRelationalDaoReadOnlyContext() {
        Company company = Company.builder()
                .companyId("CMPID1")
                .name("COMP1")
                .build();
        Company company2 = Company.builder()
                .companyId("CMPID2")
                .name("COMP2")
                .build();
        Department eng = Department.builder()
                .name("ENGINEERING")
                .company(company)
                .build();

        Department fin = Department.builder()
                .name("FINANCE")
                .company(company)
                .build();

        company.setDepartments(ImmutableList.of(eng, fin));
        companyRelationalDao.save(company.getCompanyId(), company);
        companyRelationalDao.save(company.getCompanyId(), company2);


        val criteria = DetachedCriteria.forClass(Company.class)
                .add(Restrictions.in("companyId", Sets.newHashSet(company.getCompanyId(), company2.getCompanyId())));

        val deptCriteria = DetachedCriteria.forClass(Department.class)
                .add(Restrictions.in("company.companyId", Sets.newHashSet(company.getCompanyId(), company2.getCompanyId())));


//        val res = companyRelationalDao.select(company.getCompanyId(),criteria, 0, Integer.MAX_VALUE);
//        System.out.println(res.size());
//
//        val res2 = companyRelationalDao.readOnlyExecutor(company.getCompanyId(), criteria, false)
//                .readAugmentMultiParent(departmentRelationalDao, deptCriteria, 0, Integer.MAX_VALUE, (parents, children) -> {
//                    parents.forEach(parent-> {
//                        children.
//                            })
//                    children.forEach(e -> System.out.println(e.getName()));
//                    parent.setDepartments(children);
//                })
//                .execute();
//        res2.get().stream().forEach(e-> {
//                    System.out.println(e.getCompanyId());
//                });
       // System.out.println(res2.get().size());
    }

    @Entity
    @Table(name="departments")
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

        @ManyToOne
        @JoinColumn(name = "company_id")
        private Company company;
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
        private String companyId;

        @Column(name = "name", nullable = false)
        private String name;

        @OneToMany(mappedBy = "company")
        @Cascade(CascadeType.ALL)
        private List<Department> departments;


    }
}

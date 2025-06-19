package io.appform.dropwizard.sharding.dao.locktest;

import com.google.common.collect.Lists;
import io.appform.dropwizard.sharding.DBShardingBundleBase;
import io.appform.dropwizard.sharding.ShardInfoProvider;
import io.appform.dropwizard.sharding.config.ShardingBundleOptions;
import io.appform.dropwizard.sharding.dao.MultiTenantRelationalDao;
import io.appform.dropwizard.sharding.dao.RelationalDao;
import io.appform.dropwizard.sharding.dao.interceptors.DaoClassLocalObserver;
import io.appform.dropwizard.sharding.observers.internal.TerminalTransactionObserver;
import io.appform.dropwizard.sharding.query.QuerySpec;
import io.appform.dropwizard.sharding.query.QueryUtils;
import io.appform.dropwizard.sharding.sharding.BalancedShardManager;
import io.appform.dropwizard.sharding.sharding.ShardManager;
import lombok.SneakyThrows;
import lombok.val;
import org.hibernate.SessionFactory;
import org.hibernate.boot.registry.StandardServiceRegistry;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
import org.hibernate.cfg.Configuration;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.persistence.criteria.Order;
import javax.persistence.criteria.Predicate;
import javax.persistence.criteria.Root;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class ParentChildTest {

    private List<SessionFactory> sessionFactories = Lists.newArrayList();


    private RelationalDao<ParentClass> parentClassRelationalDao;

    @BeforeEach
    public void before() {

        for (int i = 0; i < 2; i++) {
            SessionFactory sessionFactory = buildSessionFactory(String.format("db_%d", i));
            sessionFactories.add(sessionFactory);
        }
        final ShardManager shardManager = new BalancedShardManager(sessionFactories.size());
        final ShardingBundleOptions shardingOptions = ShardingBundleOptions.builder().skipReadOnlyTransaction(true).build();
        final ShardInfoProvider shardInfoProvider = new ShardInfoProvider("default");

        parentClassRelationalDao = new RelationalDao<>(DBShardingBundleBase.DEFAULT_NAMESPACE,
                new MultiTenantRelationalDao<>(Map.of(DBShardingBundleBase.DEFAULT_NAMESPACE, sessionFactories),
                        ParentClass.class, Map.of(DBShardingBundleBase.DEFAULT_NAMESPACE, shardManager),
                        Map.of(DBShardingBundleBase.DEFAULT_NAMESPACE, shardingOptions),
                        Map.of(DBShardingBundleBase.DEFAULT_NAMESPACE, shardInfoProvider),
                        new DaoClassLocalObserver(new TerminalTransactionObserver())));
    }


//    @SneakyThrows
//    @Test
//    public void test() {
//        String parentKey = "1";
//        ParentClass childClass1 = ChildBClass.builder().childColumn("CHILD1VALUE").parentColumn(1).build();
//        ParentClass childClass2 = Child2Class.builder().childColumn("CHILD2VALUE").parentColumn(2).build();
//        parentClassRelationalDao.save(parentKey, childClass1);
//        parentClassRelationalDao.save(parentKey, childClass2);
//
////        QuerySpec<Child1Class, Child1Class> querySpec = (queryRoot, query, criteriaBuilder) -> {
////           // QueryUtils.equalityFilter(criteriaBuilder, queryRoot, "childColumn", "SOMEVALUE");
////             criteriaBuilder.and(
////                    criteriaBuilder.equal(queryRoot.type(), Child1Class.class),
////                    criteriaBuilder.like(((Root<Child1Class>) (Root<?>) queryRoot).get("childColumn"), "CHILDVALUE")
////            );
////
////        };
//
////        CriteriaBuilder criteriaBuilder = entityManager.getCriteriaBuilder();
////        CriteriaQuery<Car> criteriaQuery = criteriaBuilder.createQuery(Car.class);
////        Root<Car> car= criteriaQuery.from(Car.class);
////        Root<Soccerball> soccerballs = criteriaQuery.from(SoccerBall.class);
////        Predicate [] restrictions = new Predicate[]{  criteriaBuiler.equal(car.get(carModel), "Civic"), criteriaBuilder.equal(soccerball.get("numberOfKicks"),20), criteriaBuilder.equal(soccerball.get(SoccerBall_.id),car.get(Car_.id))};
////        criteriaQuery.where(restrictions);
////        TypedQuery<Car> typedQuery = entityManager.createQuery(criteriaQuery);
////        Car carWithSoccerBalls = typedQuery.getSingleResult();
//
//        QuerySpec<ParentClass, ParentClass> querySpec = (queryRoot, query, criteriaBuilder) -> {
//
//            Predicate parentColumnCheck = criteriaBuilder.equal(queryRoot.get("type"), "CHILD1");

    /// /            Predicate childColumnCheck = criteriaBuilder.equal(query.from(Child1Class.class).get("childColumn"), "CHILD1VALUE");
    /// /            query.where(criteriaBuilder.and(parentColumnCheck, childColumnCheck));
//            //query.where(parentColumnCheck);
//            query.select(queryRoot);
//            query.orderBy(criteriaBuilder.desc(queryRoot.get("id")));
//        };
//        val data = parentClassRelationalDao.select(parentKey, querySpec, 0, Integer.MAX_VALUE);
//        data.stream().forEach(e -> {
//            try {
//                System.out.println(new ObjectMapper().writeValueAsString(e));
//            } catch (JsonProcessingException ex) {
//                //throw new RuntimeException(ex);
//            }
//        });
//    }
    @SneakyThrows
    @Test
    void test() {
        val parentKey = "123";
        val parentA = "PARENT_A";
        val parentB = "PARENT_B";
        val childAColumnValue = "CHILD-A-VALUE-1";
        val childBColumnValue = "CHILD-B-VALUE-2";

        val dataList = List.of(
                ChildAClass.builder().parentKey(parentKey).childAColumn("CHILD-A-VALUE-1").parentColumn(parentA).build(),
                ChildAClass.builder().parentKey(parentKey).childAColumn("CHILD-A-VALUE-2").parentColumn(parentB).build(),
                ChildAClass.builder().parentKey(parentKey).childAColumn("CHILD-A-VALUE-3").parentColumn(parentA).build(),
                ChildAClass.builder().parentKey(parentKey).childAColumn("CHILD-A-VALUE-4").parentColumn(parentB).build(),

                ChildBClass.builder().parentKey(parentKey).childBColumn("CHILD-B-VALUE-1").parentColumn(parentA).build(),
                ChildBClass.builder().parentKey(parentKey).childBColumn("CHILD-B-VALUE-2").parentColumn(parentB).build(),
                ChildBClass.builder().parentKey(parentKey).childBColumn("CHILD-B-VALUE-3").parentColumn(parentA).build(),
                ChildBClass.builder().parentKey(parentKey).childBColumn("CHILD-B-VALUE-4").parentColumn(parentB).build()
        );
        for (ParentClass data : dataList) {
            parentClassRelationalDao.save(data.getParentKey(), data);
        }

        // Querying with Parent column -> WORKS
        Assertions.assertDoesNotThrow(() -> {
            QuerySpec<ParentClass, ParentClass> querySpec = (queryRoot, query, criteriaBuilder) -> {
                query.where(QueryUtils.equalityFilter(criteriaBuilder, queryRoot, "parentColumn", parentA));
            };
            val parentColumnQuery = parentClassRelationalDao.select(parentKey, querySpec, 0, Integer.MAX_VALUE);
            Assertions.assertNotNull(parentColumnQuery);
        });

        // Querying with Child column -> DOES NOT WORKS
        // Error : Unable to locate Attribute  with the given name [childAColumn] on this ManagedType [io.appform.dropwizard.sharding.dao.locktest.ParentClass]
        // Basically childField is not present in parentClass
        Assertions.assertThrows(IllegalArgumentException.class, () -> {
            QuerySpec<ParentClass, ParentClass> querySpec = (queryRoot, query, criteriaBuilder) -> {
                query.where(QueryUtils.equalityFilter(criteriaBuilder, queryRoot, "childAColumn", childAColumnValue));
            };
            val parentColumnQuery = parentClassRelationalDao.select(parentKey, querySpec, 0, Integer.MAX_VALUE);
            Assertions.assertNotNull(parentColumnQuery);
        });

        // FIX FOR ABOVE ISSUE
        Assertions.assertDoesNotThrow(() -> {
            QuerySpec<ParentClass, ParentClass> querySpec = (queryRoot, query, criteriaBuilder) -> {
                Root<ChildAClass> childAClassRoot = criteriaBuilder.treat(queryRoot, ChildAClass.class);
                Predicate parentColumnPredicate = QueryUtils.equalityFilter(criteriaBuilder, queryRoot, "parentColumn", parentA);
                Predicate childAColumnPredicate = QueryUtils.equalityFilter(criteriaBuilder, childAClassRoot, "childAColumn", childAColumnValue);
                query.where(criteriaBuilder.and(parentColumnPredicate, childAColumnPredicate));
            };
            val parentColumnQuery = parentClassRelationalDao.select(parentKey, querySpec, 0, Integer.MAX_VALUE);
            Assertions.assertNotNull(parentColumnQuery);
        });

        // IN filter
        Assertions.assertDoesNotThrow(() -> {
            QuerySpec<ParentClass, ParentClass> querySpec = (queryRoot, query, criteriaBuilder) -> {
                Predicate parentColumnPredicate = QueryUtils.inFilter(queryRoot, "type", List.of(Category.CATEGORYA));
                query.where(parentColumnPredicate);
            };
            val parentColumnQuery = parentClassRelationalDao.select(parentKey, querySpec, 0, Integer.MAX_VALUE);
            Assertions.assertNotNull(parentColumnQuery);
            for (val ele : parentColumnQuery) {
                Assertions.assertEquals(Category.CATEGORYA, ele.getType());
            }

        });

        // NOT IN filter
        Assertions.assertDoesNotThrow(() -> {
            QuerySpec<ParentClass, ParentClass> querySpec = (queryRoot, query, criteriaBuilder) -> {
                Predicate parentColumnPredicate = QueryUtils.notInFilter(criteriaBuilder, queryRoot, "type", List.of(Category.CATEGORYA));
                query.where(parentColumnPredicate);
            };
            val parentColumnQuery = parentClassRelationalDao.select(parentKey, querySpec, 0, Integer.MAX_VALUE);
            Assertions.assertNotNull(parentColumnQuery);
            for (val ele : parentColumnQuery) {
                Assertions.assertNotEquals(Category.CATEGORYA, ele.getType());
            }
        });

        // Order By
        Assertions.assertDoesNotThrow(() -> {
            QuerySpec<ParentClass, ParentClass> querySpec = (queryRoot, query, criteriaBuilder) -> {
                Predicate parentColumnPredicate = QueryUtils.notInFilter(criteriaBuilder, queryRoot, "type", List.of(Category.CATEGORYA));
                Order order = QueryUtils.ascOrder(criteriaBuilder, queryRoot, "type");
                Order order2 = QueryUtils.descOrder(criteriaBuilder, queryRoot, "parentColumn");
                query.where(parentColumnPredicate).orderBy(order2, order);
            };
            val parentColumnQuery = parentClassRelationalDao.select(parentKey, querySpec, 0, Integer.MAX_VALUE);
            Assertions.assertNotNull(parentColumnQuery);
            for (val ele : parentColumnQuery) {
                Assertions.assertNotEquals(Category.CATEGORYA, ele.getType());
            }
        });

    }


    private SessionFactory buildSessionFactory(String dbName) {
        Configuration configuration = new Configuration();
        configuration.setProperty("hibernate.dialect", "org.hibernate.dialect.H2Dialect");
        configuration.setProperty("hibernate.connection.driver_class", "org.h2.Driver");
        configuration.setProperty("hibernate.connection.url", "jdbc:h2:mem:" + dbName);
        configuration.setProperty("hibernate.hbm2ddl.auto", "create");
        configuration.setProperty("hibernate.current_session_context_class", "managed");
        configuration.setProperty("hibernate.show_sql", "true");

        configuration.addAnnotatedClass(ParentClass.class);
        configuration.addAnnotatedClass(ChildAClass.class);
        configuration.addAnnotatedClass(ChildBClass.class);

        StandardServiceRegistry serviceRegistry
                = new StandardServiceRegistryBuilder().applySettings(
                configuration.getProperties()).build();
        return configuration.buildSessionFactory(serviceRegistry);
    }
}

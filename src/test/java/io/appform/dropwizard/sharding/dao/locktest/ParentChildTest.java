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

public class ParentChildTest {

    private static final String PARENT_KEY = "123";
    private static final String PARENT_A_VALUE = "PARENT_A";
    private static final String PARENT_B_VALUE = "PARENT_B";
    private static final String CHILD_A_COLUMN_VALUE = "CHILD-A-VALUE-1";
    private static final String CHILD_B_COLUMN_VALUE = "CHILD-B-VALUE-2";

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
        setupStore();
    }

    @SneakyThrows
    private void setupStore() {
        List<ParentClass> dataList = List.of(
                ChildAClass.builder().parentKey(PARENT_KEY).childAColumn("CHILD-A-VALUE-1").parentColumn(PARENT_A_VALUE).build(),
                ChildAClass.builder().parentKey(PARENT_KEY).childAColumn("CHILD-A-VALUE-2").parentColumn(PARENT_B_VALUE).build(),
                ChildAClass.builder().parentKey(PARENT_KEY).childAColumn("CHILD-A-VALUE-3").parentColumn(PARENT_A_VALUE).build(),
                ChildAClass.builder().parentKey(PARENT_KEY).childAColumn("CHILD-A-VALUE-4").parentColumn(PARENT_B_VALUE).build(),

                ChildBClass.builder().parentKey(PARENT_KEY).childBColumn("CHILD-B-VALUE-1").parentColumn(PARENT_A_VALUE).build(),
                ChildBClass.builder().parentKey(PARENT_KEY).childBColumn("CHILD-B-VALUE-2").parentColumn(PARENT_B_VALUE).build(),
                ChildBClass.builder().parentKey(PARENT_KEY).childBColumn("CHILD-B-VALUE-3").parentColumn(PARENT_A_VALUE).build(),
                ChildBClass.builder().parentKey(PARENT_KEY).childBColumn("CHILD-B-VALUE-4").parentColumn(PARENT_B_VALUE).build()
        );
        for (ParentClass data : dataList) {
            parentClassRelationalDao.save(data.getParentKey(), data);
        }
    }

    @SneakyThrows
    @Test
    void testQueryingByParent() {
        List<String> parentColumnValues = List.of(PARENT_A_VALUE, PARENT_B_VALUE);
        for (String parentValue : parentColumnValues) {
            QuerySpec<ParentClass, ParentClass> querySpec = (queryRoot, query, criteriaBuilder) -> {
                query.where(QueryUtils.equalityFilter(criteriaBuilder, queryRoot, "parentColumn", parentValue));
            };
            List<ParentClass> parentAData = parentClassRelationalDao.select(PARENT_KEY, querySpec, 0, Integer.MAX_VALUE);
            Assertions.assertNotNull(parentAData);
            for (ParentClass ele : parentAData) {
                Assertions.assertEquals(parentValue, ele.getParentColumn());
            }
        }
    }

    @SneakyThrows
    @Test
    void testQueryingByChildColumn() {
        List<String> childColumnValues = List.of(CHILD_A_COLUMN_VALUE, CHILD_B_COLUMN_VALUE);

        for (String childValue : childColumnValues) {
//        Querying with Child column -> DOES NOT WORKS
//        Error : Unable to locate Attribute  with the given name [childAColumn] on this ManagedType [io.appform.dropwizard.sharding.dao.locktest.ParentClass]
//        Basically childField is not present in parentClass
            QuerySpec<ParentClass, ParentClass> wrondQuerySpec = (queryRoot, query, criteriaBuilder) -> {
                query.where(QueryUtils.equalityFilter(criteriaBuilder, queryRoot, "childAColumn", childValue));
            };
            Assertions.assertThrows(IllegalArgumentException.class, () -> {
                parentClassRelationalDao.select(PARENT_KEY, wrondQuerySpec, 0, Integer.MAX_VALUE);
            });
        }


//        // FIX FOR ABOVE ISSUE
        Assertions.assertDoesNotThrow(() -> {
            QuerySpec<ParentClass, ParentClass> querySpec = (queryRoot, query, criteriaBuilder) -> {
                Root<ChildAClass> childAClassRoot = criteriaBuilder.treat(queryRoot, ChildAClass.class);
                Predicate parentColumnPredicate = QueryUtils.equalityFilter(criteriaBuilder, queryRoot, "parentColumn", PARENT_A_VALUE);
                Predicate childAColumnPredicate = QueryUtils.equalityFilter(criteriaBuilder, childAClassRoot, "childAColumn", CHILD_A_COLUMN_VALUE);
                query.where(criteriaBuilder.and(parentColumnPredicate, childAColumnPredicate));
            };
            List<ParentClass> parentColumnQuery = parentClassRelationalDao.select(PARENT_KEY, querySpec, 0, Integer.MAX_VALUE);
            Assertions.assertNotNull(parentColumnQuery);
        });
    }

    @SneakyThrows
    @Test
    void testQueryingInClauseWithEnum() {
        Assertions.assertDoesNotThrow(() -> {
            QuerySpec<ParentClass, ParentClass> querySpec = (queryRoot, query, criteriaBuilder) -> {
                Predicate parentColumnPredicate = QueryUtils.inFilter(queryRoot, "type", List.of(Category.CATEGORYA));
                query.where(parentColumnPredicate);
            };
            List<ParentClass> parentColumnQuery = parentClassRelationalDao.select(PARENT_KEY, querySpec, 0, Integer.MAX_VALUE);
            Assertions.assertNotNull(parentColumnQuery);
            for (ParentClass ele : parentColumnQuery) {
                Assertions.assertEquals(Category.CATEGORYA, ele.getType());
            }
        });
    }

    @SneakyThrows
    @Test
    void testQueryingNotInClause() {
        Assertions.assertDoesNotThrow(() -> {
            QuerySpec<ParentClass, ParentClass> querySpec = (queryRoot, query, criteriaBuilder) -> {
                Predicate parentColumnPredicate = QueryUtils.inFilter(queryRoot, "parentColumn", List.of(PARENT_A_VALUE));
                query.where(parentColumnPredicate);
            };
            List<ParentClass> parentColumnQuery = parentClassRelationalDao.select(PARENT_KEY, querySpec, 0, Integer.MAX_VALUE);
            Assertions.assertNotNull(parentColumnQuery);
            for (ParentClass ele : parentColumnQuery) {
                Assertions.assertEquals(PARENT_A_VALUE, ele.getParentColumn());
            }
        });
    }


    @SneakyThrows
    @Test
    void testOrderBy() {
        Assertions.assertDoesNotThrow(() -> {
            QuerySpec<ParentClass, ParentClass> querySpec = (queryRoot, query, criteriaBuilder) -> {
                Predicate parentColumnPredicate = QueryUtils.notInFilter(criteriaBuilder, queryRoot, "type", List.of(Category.CATEGORYA));
                Order order = QueryUtils.ascOrder(criteriaBuilder, queryRoot, "type");
                Order order2 = QueryUtils.descOrder(criteriaBuilder, queryRoot, "parentColumn");
                query.where(parentColumnPredicate).orderBy(order2, order);
            };
            List<ParentClass> parentColumnQuery = parentClassRelationalDao.select(PARENT_KEY, querySpec, 0, Integer.MAX_VALUE);
            Assertions.assertNotNull(parentColumnQuery);
        });
    }


        private SessionFactory buildSessionFactory (String dbName){
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

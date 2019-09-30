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

package io.appform.dropwizard.sharding.dao;

import com.google.common.base.Preconditions;
import io.dropwizard.hibernate.AbstractDAO;
import io.appform.dropwizard.sharding.utils.ShardCalculator;
import io.appform.dropwizard.sharding.utils.Transactions;
import lombok.Builder;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import lombok.val;
import org.apache.commons.lang3.reflect.FieldUtils;
import org.hibernate.*;
import org.hibernate.criterion.DetachedCriteria;
import org.hibernate.criterion.Projections;
import org.hibernate.criterion.Restrictions;

import javax.persistence.Id;
import java.lang.reflect.Field;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * A dao used to work with entities related to a parent shard. The parent may or maynot be physically present.
 * A murmur 128 hash of the string parent key is used to route the save and retrieve calls from the proper shard.
 */
@Slf4j
public class RelationalDao<T> implements ShardedDao<T> {

    private final class RelationalDaoPriv extends AbstractDAO<T> {

        private final SessionFactory sessionFactory;

        /**
         * Creates a new DAO with a given session provider.
         *
         * @param sessionFactory a session provider
         */
        public RelationalDaoPriv(SessionFactory sessionFactory) {
            super(sessionFactory);
            this.sessionFactory = sessionFactory;
        }

        T get(Object lookupKey) {
            return uniqueResult(currentSession()
                                .createCriteria(entityClass)
                                .add(
                                        Restrictions.eq(keyField.getName(), lookupKey)));
        }

        T save(T entity) {
            return persist(entity);
        }

        boolean saveAll(Collection<T> entities) {
            for (T entity : entities) {
                persist(entity);
            }
            return true;
        }

        void update(T oldEntity, T entity) {
            currentSession().evict(oldEntity); //Detach .. otherwise update is a no-op
            currentSession().update(entity);
        }

        List<T> select(SelectParamPriv selectParam) {
            val criteria = selectParam.criteria.getExecutableCriteria(currentSession());
            criteria.setFirstResult(selectParam.start);
            criteria.setMaxResults(selectParam.numRows);
            return list(criteria);
        }

        ScrollableResults scroll(ScrollParamPriv scrollDetails) {
            final Criteria criteria = scrollDetails.getCriteria().getExecutableCriteria(currentSession());
            return criteria.scroll(ScrollMode.FORWARD_ONLY);
        }

        long count(DetachedCriteria criteria) {
            return  (long)criteria.getExecutableCriteria(currentSession())
                            .setProjection(Projections.rowCount())
                            .uniqueResult();
        }

    }

    @Builder
    private static class SelectParamPriv {
        DetachedCriteria criteria;
        int start;
        int numRows;
    }

    @Builder
    private static class ScrollParamPriv {
        @Getter
        private DetachedCriteria criteria;
    }

    private List<RelationalDaoPriv> daos;
    private final Class<T> entityClass;
    @Getter
    private final ShardCalculator<String> shardCalculator;
    private final Field keyField;

    /**
     * Create a relational DAO.
     * @param sessionFactories List of session factories. One for each shard.
     * @param entityClass The class for which the dao will be used.
     * @param shardCalculator
     */
    public RelationalDao(
            List<SessionFactory> sessionFactories, Class<T> entityClass,
            ShardCalculator<String> shardCalculator ) {
        this.shardCalculator = shardCalculator;
        this.daos = sessionFactories.stream().map(RelationalDaoPriv::new).collect(Collectors.toList());
        this.entityClass = entityClass;

        Field fields[] = FieldUtils.getFieldsWithAnnotation(entityClass, Id.class);
        Preconditions.checkArgument(fields.length != 0, "A field needs to be designated as @Id");
        Preconditions.checkArgument(fields.length == 1, "Only one field can be designated as @Id");
        keyField = fields[0];
        if(!keyField.isAccessible()) {
            try {
                keyField.setAccessible(true);
            } catch (SecurityException e) {
                log.error("Error making key field accessible please use a public method and mark that as @Id", e);
                throw new IllegalArgumentException("Invalid class, DAO cannot be created.", e);
            }
        }
    }


    public Optional<T> get(String parentKey, Object key) throws Exception {
        return Optional.ofNullable(get(parentKey, key, t-> t));
    }

    public<U> U get(String parentKey, Object key, Function<T, U> function) {
        int shardId = shardCalculator.shardId(parentKey);
        RelationalDaoPriv dao = daos.get(shardId);
        return Transactions.execute(dao.sessionFactory, true, dao::get, key, function);
    }

    public Optional<T> save(String parentKey, T entity) throws Exception {
        return Optional.ofNullable(save(parentKey, entity, t -> t));
    }

    public <U> U save(String parentKey, T entity, Function<T, U> handler) {
        int shardId = shardCalculator.shardId(parentKey);
        RelationalDaoPriv dao = daos.get(shardId);
        return Transactions.execute(dao.sessionFactory, false, dao::save, entity, handler);
    }

    public boolean saveAll(String parentKey, Collection<T> entities) {
        int shardId = shardCalculator.shardId(parentKey);
        RelationalDaoPriv dao = daos.get(shardId);
        return Transactions.execute(dao.sessionFactory, false, dao::saveAll, entities);
    }

    <U> void save(LookupDao.LockedContext<U> context, T entity) {
        RelationalDaoPriv dao = daos.get(context.getShardId());
        Transactions.execute(context.getSessionFactory(), false, dao::save, entity, t->t, false);
    }

    <U> void save(LookupDao.LockedContext<U> context, T entity, Function<T, T> handler) {
        RelationalDaoPriv dao = daos.get(context.getShardId());
        Transactions.execute(context.getSessionFactory(), false, dao::save, entity, handler, false);
    }

    <U> boolean update(LookupDao.LockedContext<U> context, Object id, Function<T, T> updater) {
        RelationalDaoPriv dao = daos.get(context.getShardId());
        return update(context.getSessionFactory(), dao, id, updater, false);
    }

    <U> boolean update(LookupDao.LockedContext<U> context,
                       DetachedCriteria criteria,
                       Function<T, T> updater,
                       BooleanSupplier updateNext) {
        final RelationalDaoPriv dao = daos.get(context.getShardId());

        try {
            final ScrollParamPriv scrollParam = ScrollParamPriv.builder()
                    .criteria(criteria)
                    .build();

            return Transactions.<ScrollableResults, ScrollParamPriv, Boolean>execute(context.getSessionFactory(), true, dao::scroll, scrollParam, scrollableResults -> {
                boolean updateNextObject = true;
                try {
                    while(scrollableResults.next() && updateNextObject) {
                        final T entity = (T) scrollableResults.get(0);
                        if (null == entity) {
                            return false;
                        }
                        final T newEntity = updater.apply(entity);
                        if(null == newEntity) {
                            return false;
                        }
                        dao.update(entity, newEntity);
                        updateNextObject = updateNext.getAsBoolean();
                    }
                }
                finally {
                    scrollableResults.close();
                }
                return true;
            }, false);
        } catch (Exception e) {
            throw new RuntimeException("Error updating entity with scroll: " + criteria, e);
        }
    }

    public boolean update(String parentKey, Object id, Function<T, T> updater) {
        int shardId = shardCalculator.shardId(parentKey);
        RelationalDaoPriv dao = daos.get(shardId);
        return update(dao.sessionFactory, dao, id, updater, true);
    }

    public <U> U runInSession(String id, Function<Session, U> handler) {
        int shardId = shardCalculator.shardId(id);
        RelationalDaoPriv dao = daos.get(shardId);
        return Transactions.execute(dao.sessionFactory, handler);
    }

    private boolean update(SessionFactory daoSessionFactory, RelationalDaoPriv dao, Object id, Function<T, T> updater, boolean completeTransaction){
        try {
            return Transactions.<T, Object, Boolean>execute(daoSessionFactory, true, dao::get, id, (T entity) -> {
                if(null == entity) {
                    return false;
                }
                T newEntity = updater.apply(entity);
                if(null == newEntity) {
                    return false;
                }
                dao.update(entity, newEntity);
                return true;
            }, completeTransaction);
        } catch (Exception e) {
            throw new RuntimeException("Error updating entity: " + id, e);
        }
    }

    public boolean update(String parentKey, DetachedCriteria criteria, Function<T, T> updater) {
        int shardId = shardCalculator.shardId(parentKey);
        RelationalDaoPriv dao = daos.get(shardId);
        try {
            SelectParamPriv selectParam = SelectParamPriv.builder()
                                                .criteria(criteria)
                                                .start(0)
                                                .numRows(1)
                                                .build();
            return Transactions.<List<T>, SelectParamPriv, Boolean>execute(dao.sessionFactory, true, dao::select, selectParam, (List<T> entityList) -> {
                if(entityList == null || entityList.isEmpty()) {
                    return false;
                }
                T oldEntity = entityList.get(0);
                if(null == oldEntity) {
                    return false;
                }
                T newEntity = updater.apply(oldEntity);
                if(null == newEntity) {
                    return false;
                }
                dao.update(oldEntity, newEntity);
                return true;
            });
        } catch (Exception e) {
            throw new RuntimeException("Error updating entity with criteria: " + criteria, e);
        }
    }

    <U> boolean createOrUpdate(LookupDao.LockedContext<U> context,
                               DetachedCriteria criteria,
                               Function<T, T> updater,
                               Supplier<T> entityGenerator) {
        final RelationalDaoPriv dao = daos.get(context.getShardId());

        try {
            final SelectParamPriv selectParam = SelectParamPriv.builder()
                    .criteria(criteria)
                    .start(0)
                    .numRows(1)
                    .build();

            return Transactions.<List<T>, SelectParamPriv, Boolean>execute(context.getSessionFactory(), true, dao::select, selectParam, (List<T> entityList) -> {
                if(entityList == null || entityList.isEmpty()) {
                    Preconditions.checkNotNull(entityGenerator, "Entity generator can't be null");
                    final T newEntity = entityGenerator.get();
                    Preconditions.checkNotNull(newEntity, "Generated entity can't be null");
                    dao.save(newEntity);
                    return true;
                }

                final T oldEntity = entityList.get(0);
                if(null == oldEntity) {
                    return false;
                }
                final T newEntity = updater.apply(oldEntity);
                if(null == newEntity) {
                    return false;
                }
                dao.update(oldEntity, newEntity);
                return true;
            }, false);
        } catch (Exception e) {
            throw new RuntimeException("Error updating entity with criteria: " + criteria, e);
        }
    }

    public boolean updateAll(String parentKey, int start, int numRows, DetachedCriteria criteria, Function<T, T> updater) {
        int shardId = shardCalculator.shardId(parentKey);
        RelationalDaoPriv dao = daos.get(shardId);
        try {
            SelectParamPriv selectParam = SelectParamPriv.builder()
                    .criteria(criteria)
                    .start(start)
                    .numRows(numRows)
                    .build();
            return Transactions.<List<T>, SelectParamPriv, Boolean>execute(dao.sessionFactory, true, dao::select, selectParam, entityList -> {
                if (entityList == null || entityList.isEmpty()) {
                    return false;
                }
                for (T oldEntity : entityList) {
                    if (null == oldEntity) {
                        return false;
                    }
                    T newEntity = updater.apply(oldEntity);
                    if (null == newEntity) {
                        return false;
                    }
                    dao.update(oldEntity, newEntity);
                }
                return true;
            });
        } catch (Exception e) {
            throw new RuntimeException("Error updating entity with criteria: " + criteria, e);
        }
    }

    public List<T> select(String parentKey, DetachedCriteria criteria, int first, int numResults) throws Exception {
        List<T> select = select(parentKey, criteria, first, numResults, t -> t);
        if (select.size() > 100){
            log.debug("More than 100 rows fetched from DB entity: {} parentKey: {} rows: {}",
                    entityClass.getName(), parentKey, select.size());
        }
        return select;
    }

    public<U> U select(String parentKey, DetachedCriteria criteria, int first, int numResults, Function<List<T>, U> handler) throws Exception {
        int shardId = shardCalculator.shardId(parentKey);
        RelationalDaoPriv dao = daos.get(shardId);
        SelectParamPriv selectParam = SelectParamPriv.<T>builder()
                .criteria(criteria)
                .start(first)
                .numRows(numResults)
                .build();
        return Transactions.execute(dao.sessionFactory, true, dao::select, selectParam, handler);
    }

    public long count(String parentKey, DetachedCriteria criteria) {
        int shardId = shardCalculator.shardId(parentKey);
        RelationalDaoPriv dao = daos.get(shardId);
        return Transactions.<Long, DetachedCriteria>execute(dao.sessionFactory, true, dao::count, criteria);
    }

    public boolean exists(String parentKey, Object key) {
        int shardId = shardCalculator.shardId(parentKey);
        RelationalDaoPriv dao = daos.get(shardId);
        Optional<T> result = Transactions.<T, Object>executeAndResolve(dao.sessionFactory, true, dao::get, key);
        return result.isPresent();
    }

    public List<T> scatterGather(DetachedCriteria criteria, int start, int numRows) {
        return daos.stream().map(dao -> {
            try {
                SelectParamPriv selectParam = SelectParamPriv.<T>builder()
                        .criteria(criteria)
                        .start(start)
                        .numRows(numRows)
                        .build();
                return Transactions.execute(dao.sessionFactory, true, dao::select, selectParam);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }).flatMap(Collection::stream).collect(Collectors.toList());
    }

    protected Field getKeyField() {
        return this.keyField;
    }
}

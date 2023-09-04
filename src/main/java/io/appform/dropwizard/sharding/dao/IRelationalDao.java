package io.appform.dropwizard.sharding.dao;

import io.appform.dropwizard.sharding.utils.ShardCalculator;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.function.Supplier;
import org.hibernate.Session;
import org.hibernate.criterion.DetachedCriteria;

public interface IRelationalDao<T> {

  public Optional<T> get(String parentKey, Object key) throws Exception;

  <U> U get(String parentKey, Object key, Function<T, U> function);

  Optional<T> save(String parentKey, T entity) throws Exception;

  <U> U save(String parentKey, T entity, Function<T, U> handler);

  boolean saveAll(String parentKey, Collection<T> entities);

  boolean update(String parentKey, Object id, Function<T, T> updater);

  <U> U runInSession(String id, Function<Session, U> handler);

  boolean update(String parentKey, DetachedCriteria criteria, Function<T, T> updater);

  int updateUsingQuery(String parentKey, UpdateOperationMeta updateOperationMeta);

  <U> int updateUsingQuery(LockedContext<U> lockedContext, UpdateOperationMeta updateOperationMeta);

  LockedContext<T> lockAndGetExecutor(String parentKey, DetachedCriteria criteria);

  LockedContext<T> saveAndGetExecutor(String parentKey, T entity);

  boolean updateAll(String parentKey, int start, int numRows, DetachedCriteria criteria,
      Function<T, T> updater);

  List<T> select(String parentKey, DetachedCriteria criteria, int first, int numResults)
      throws Exception;

  <U> U select(String parentKey, DetachedCriteria criteria, int first, int numResults,
      Function<List<T>, U> handler) throws Exception;

  long count(String parentKey, DetachedCriteria criteria);

  boolean exists(String parentKey, Object key);

  List<Long> countScatterGather(DetachedCriteria criteria);

  List<T> scatterGather(DetachedCriteria criteria, int start, int numRows);

  ShardCalculator<String> getShardCalculator();

  <U> boolean update(LockedContext<U> context, Object id, Function<T, T> updater);

  <U> void save(LockedContext<U> context, T entity);

  <U> void save(LockedContext<U> context, T entity, Function<T, T> handler);

  <U> boolean createOrUpdate(LockedContext<U> context, DetachedCriteria criteria,
      Function<T, T> updater, Supplier<T> entityGenerator);

  <U> boolean update(LockedContext<U> context, DetachedCriteria criteria, Function<T, T> updater,
      BooleanSupplier updateNext);
}

package io.appform.dropwizard.sharding.dao;

import io.appform.dropwizard.sharding.config.ShardingBundleOptions;
import io.appform.dropwizard.sharding.dao.LookupDao.ReadOnlyContext;
import io.appform.dropwizard.sharding.utils.ShardCalculator;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Supplier;
import org.hibernate.Session;
import org.hibernate.criterion.DetachedCriteria;

public interface ILookupDao<T> {

  Optional<T> get(String key) throws Exception;

  <U> U get(String key, Function<T, U> handler) throws Exception;

  boolean exists(String key) throws Exception;

  Optional<T> save(T entity) throws Exception;

  <U> U save(T entity, Function<T, U> handler) throws Exception;

  boolean updateInLock(String id, Function<Optional<T>, T> updater);

  boolean update(String id, Function<Optional<T>, T> updater);

  int updateUsingQuery(String id, UpdateOperationMeta updateOperationMeta);

  LockedContext<T> lockAndGetExecutor(String id);

  ReadOnlyContext<T> readOnlyExecutor(String id);

  ReadOnlyContext<T> readOnlyExecutor(String id, Supplier<Boolean> entityPopulator);

  LockedContext<T> saveAndGetExecutor(T entity);

  List<T> scatterGather(DetachedCriteria criteria);

  List<Long> count(DetachedCriteria criteria);

  List<T> get(List<String> keys);

  <U> U runInSession(String id, Function<Session, U> handler);

  boolean delete(String id);

  ShardCalculator<String> getShardCalculator();

  ShardingBundleOptions getShardingOptions();
}

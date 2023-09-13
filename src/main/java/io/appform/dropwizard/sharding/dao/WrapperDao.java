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

import io.appform.dropwizard.sharding.sharding.ShardedTransaction;
import io.appform.dropwizard.sharding.utils.ShardCalculator;
import io.appform.dropwizard.sharding.utils.TransactionHandler;
import io.dropwizard.hibernate.AbstractDAO;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import net.sf.cglib.proxy.Enhancer;
import net.sf.cglib.proxy.MethodInterceptor;
import org.apache.commons.lang3.ArrayUtils;
import org.hibernate.SessionFactory;

import java.lang.reflect.InvocationTargetException;
import java.util.List;
import java.util.stream.Collectors;

/**
 * A dao used to wrap custom Dao's so that all operations on the DAO's are routed to the same shard for a parent key.
 * The parent may or may not be physically present.
 * A murmur 128 hash of the string parent key is used to route the save and retrieve calls from the proper shard.
 *
 * <b>Note:</b>
 * - Methods in the custom dao will be transactional only if they are annotaded with {@link ShardedTransaction}
 * - Use {@link RelationalDao} where-ever possible as it will be slight more performant than this due to lack of any proxy.
 */
@Slf4j
public class WrapperDao<T, DaoType extends AbstractDAO<T>> implements ShardedDao<T> {

    private List<DaoType> daos;
    @Getter
    private final ShardCalculator<String> shardCalculator;

    /**
     * Create a relational DAO.
     *
     * @param sessionFactories List of session factories. One for each shard.
     * @param daoClass         Class for the dao.
     * @param shardCalculator  {@link ShardCalculator} for finding shard
     */
    public WrapperDao(List<SessionFactory> sessionFactories, Class<DaoType> daoClass, ShardCalculator<String> shardCalculator) {
        this(sessionFactories, daoClass, null, null, shardCalculator);
    }

    /**
     * Create a relational DAO.
     *
     * @param sessionFactories             List of session factories, one for each shard
     * @param daoClass                     Class for the dao.
     * @param extraConstructorParamClasses Class names for constructor parameters to the DAO other than SessionFactory
     * @param extraConstructorParamObjects Objects for constructor parameters to the DAO other than SessionFactory
     * @param shardCalculator              {@link ShardCalculator} for finding shard
     */
    public WrapperDao(
            List<SessionFactory> sessionFactories, Class<DaoType> daoClass,
            Class[] extraConstructorParamClasses,
            Class[] extraConstructorParamObjects, ShardCalculator<String> shardCalculator) {
        this.shardCalculator = shardCalculator;
        this.daos = sessionFactories.stream().map((SessionFactory sessionFactory) -> {
            Enhancer enhancer = new Enhancer();
            enhancer.setUseFactory(false);
            enhancer.setSuperclass(daoClass);
            enhancer.setCallback((MethodInterceptor) (obj, method, args, proxy) -> {
                final ShardedTransaction transaction = method.getAnnotation(ShardedTransaction.class);
                if (null == transaction) {
                    return proxy.invokeSuper(obj, args);
                }
                final TransactionHandler transactionHandler = new TransactionHandler(sessionFactory, transaction.readOnly());
                try {
                    transactionHandler.beforeStart();
                    Object result = proxy.invokeSuper(obj, args);
                    transactionHandler.afterEnd();
                    return result;
                } catch (InvocationTargetException e) {
                    transactionHandler.onError();
                    throw e.getCause();
                } catch (Exception e) {
                    transactionHandler.onError();
                    throw e;
                }
            });
            return createDAOProxy(sessionFactory, enhancer, extraConstructorParamClasses, extraConstructorParamObjects);
        }).collect(Collectors.toList());
    }

    /**
     * Get a fully formed DAO that localizes all dao operations to the shard for the given parentKey.
     *
     * @param parentKey key that will be used to find the shard
     * @return Wrapper for parent dao
     */
    public DaoType forParent(final String parentKey) {
        return daos.get(shardCalculator.shardId(parentKey));
    }

    @SuppressWarnings("unchecked")
    private DaoType createDAOProxy(SessionFactory sessionFactory, Enhancer enhancer,
                                   Class[] extraConstructorParamClasses, Class[] extraConstructorParamObjects) {
        Class[] constructorClasses = new Class[]{SessionFactory.class};
        if (null != extraConstructorParamClasses) {
            ArrayUtils.addAll(constructorClasses, extraConstructorParamClasses);
        }
        Object[] constructorObjects = new Object[]{sessionFactory};

        if (null != extraConstructorParamObjects) {
            ArrayUtils.addAll(constructorObjects, extraConstructorParamClasses);
        }
        return (DaoType) enhancer.create(constructorClasses, constructorObjects);
    }

}

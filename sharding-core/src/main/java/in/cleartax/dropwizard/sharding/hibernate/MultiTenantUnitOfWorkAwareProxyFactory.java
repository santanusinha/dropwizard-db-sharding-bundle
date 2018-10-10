/*
 * Copyright 2018 Saurabh Agrawal (Cleartax)
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

package in.cleartax.dropwizard.sharding.hibernate;

import com.google.common.collect.ImmutableMap;
import io.dropwizard.hibernate.UnitOfWork;
import io.dropwizard.hibernate.UnitOfWorkAspect;
import javassist.util.proxy.Proxy;
import javassist.util.proxy.ProxyFactory;
import org.hibernate.SessionFactory;

import java.lang.reflect.InvocationTargetException;

public class MultiTenantUnitOfWorkAwareProxyFactory {
    private final ImmutableMap<String, SessionFactory> sessionFactories;

    public MultiTenantUnitOfWorkAwareProxyFactory(String name, SessionFactory sessionFactory) {
        sessionFactories = ImmutableMap.of(name, sessionFactory);
    }

    public MultiTenantUnitOfWorkAwareProxyFactory(MultiTenantHibernateBundle<?>... bundles) {
        final ImmutableMap.Builder<String, SessionFactory> sessionFactoriesBuilder = ImmutableMap.builder();
        for (MultiTenantHibernateBundle<?> bundle : bundles) {
            sessionFactoriesBuilder.put(bundle.name(), bundle.getSessionFactory());
        }
        sessionFactories = sessionFactoriesBuilder.build();
    }


    /**
     * Creates a new <b>@UnitOfWork</b> aware proxy of a class with the default constructor.
     *
     * @param clazz the specified class definition
     * @param <T>   the type of the class
     * @return a new proxy
     */
    public <T> T create(Class<T> clazz) {
        return create(clazz, new Class<?>[]{}, new Object[]{});
    }

    /**
     * Creates a new <b>@UnitOfWork</b> aware proxy of a class with an one-parameter constructor.
     *
     * @param clazz                the specified class definition
     * @param constructorParamType the type of the constructor parameter
     * @param constructorArguments the argument passed to the constructor
     * @param <T>                  the type of the class
     * @return a new proxy
     */
    public <T> T create(Class<T> clazz, Class<?> constructorParamType, Object constructorArguments) {
        return create(clazz, new Class<?>[]{constructorParamType}, new Object[]{constructorArguments});
    }

    /**
     * Creates a new <b>@UnitOfWork</b> aware proxy of a class with a complex constructor.
     *
     * @param clazz                 the specified class definition
     * @param constructorParamTypes the types of the constructor parameters
     * @param constructorArguments  the arguments passed to the constructor
     * @param <T>                   the type of the class
     * @return a new proxy
     */
    @SuppressWarnings("unchecked")
    public <T> T create(Class<T> clazz, Class<?>[] constructorParamTypes, Object[] constructorArguments) {
        final ProxyFactory factory = new ProxyFactory();
        factory.setSuperclass(clazz);

        try {
            final Proxy proxy = (Proxy) (constructorParamTypes.length == 0 ?
                    factory.createClass().getConstructor().newInstance() :
                    factory.create(constructorParamTypes, constructorArguments));
            proxy.setHandler((self, overridden, proceed, args) -> {
                final UnitOfWork unitOfWork = overridden.getAnnotation(UnitOfWork.class);
                final UnitOfWorkAspect unitOfWorkAspect = newAspect(sessionFactories);
                try {
                    unitOfWorkAspect.beforeStart(unitOfWork);
                    Object result = proceed.invoke(self, args);
                    unitOfWorkAspect.afterEnd();
                    return result;
                } catch (InvocationTargetException e) {
                    unitOfWorkAspect.onError();
                    throw e.getCause();
                } catch (Exception e) {
                    unitOfWorkAspect.onError();
                    throw e;
                } finally {
                    unitOfWorkAspect.onFinish();
                }
            });
            return (T) proxy;
        } catch (NoSuchMethodException | InstantiationException | IllegalAccessException |
                InvocationTargetException e) {
            throw new IllegalStateException("Unable to create a proxy for the class '" + clazz + "'", e);
        }
    }

    /**
     * @return a new aspect
     */
    public UnitOfWorkAspect newAspect() {
        return new UnitOfWorkAspect(sessionFactories);
    }

    /**
     * @return a new aspect
     * @param sessionFactories
     */
    public UnitOfWorkAspect newAspect(ImmutableMap<String, SessionFactory> sessionFactories) {
        return new UnitOfWorkAspect(sessionFactories);
    }
}

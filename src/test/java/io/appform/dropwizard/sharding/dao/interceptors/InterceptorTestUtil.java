package io.appform.dropwizard.sharding.dao.interceptors;

import lombok.experimental.UtilityClass;
import org.junit.Assert;
import org.slf4j.MDC;

@UtilityClass
public class InterceptorTestUtil {

    public static final String ENTITY_START = "entity.start";
    public static final String ENTITY_END = "entity.end";

    public static final String DAO_START = "dao.start";

    public static final String DAO_END = "dao.end";

    public void validateThreadLocal(final Class<?> daoClass,
                                    final Class<?> entityClass) {
        Assert.assertEquals(daoClass.getSimpleName(), MDC.get(DAO_START));
        Assert.assertEquals(daoClass.getSimpleName(), MDC.get(DAO_END));
        Assert.assertEquals(entityClass.getSimpleName(), MDC.get(ENTITY_START));
        Assert.assertEquals(entityClass.getSimpleName(), MDC.get(ENTITY_END));
    }
}

package io.appform.dropwizard.sharding.dao.interceptors;

import lombok.experimental.UtilityClass;
import org.slf4j.MDC;

import static org.junit.jupiter.api.Assertions.assertEquals;

@UtilityClass
public class InterceptorTestUtil {

    public static final String ENTITY_START = "entity.start";
    public static final String ENTITY_END = "entity.end";

    public static final String DAO_START = "dao.start";

    public static final String DAO_END = "dao.end";

    public void validateThreadLocal(final String daoType,
                                    final Class<?> entityClass) {
        assertEquals(daoType, MDC.get(DAO_START));
        assertEquals(daoType, MDC.get(DAO_END));
        assertEquals(entityClass.getSimpleName(), MDC.get(ENTITY_START));
        assertEquals(entityClass.getSimpleName(), MDC.get(ENTITY_END));
    }
}

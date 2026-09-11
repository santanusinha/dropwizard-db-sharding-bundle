package io.appform.dropwizard.sharding.dao;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.lang.reflect.Constructor;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DaoEncapsulationTest {

    @ParameterizedTest
    @ValueSource(classes = {
            MultiTenantLookupDao.class,
            MultiTenantCacheableLookupDao.class,
            MultiTenantRelationalDao.class,
            MultiTenantCacheableRelationalDao.class,
            LookupDao.class,
            CacheableLookupDao.class,
            RelationalDao.class,
            CacheableRelationalDao.class,
            WrapperDao.class
    })
    void noDaoExposesAPublicConstructor(final Class<?> daoClass) {
        final List<String> publicConstructors = Arrays.stream(daoClass.getDeclaredConstructors())
                .filter(constructor -> Modifier.isPublic(constructor.getModifiers()))
                .map(Constructor::toString)
                .collect(Collectors.toList());
        assertTrue(publicConstructors.isEmpty(),
                daoClass.getSimpleName() + " still exposes public constructors: " + publicConstructors);
    }

    @ParameterizedTest
    @ValueSource(classes = {
            MultiTenantCacheableLookupDao.class,
            MultiTenantCacheableRelationalDao.class,
            CacheableLookupDao.class,
            CacheableRelationalDao.class,
            WrapperDao.class
    })
    void leafDaosAreFinal(final Class<?> daoClass) {
        assertTrue(Modifier.isFinal(daoClass.getModifiers()),
                daoClass.getSimpleName() + " must be final");
    }

    @Test
    void baseDaosAreSealedToTheirCacheableVariants() {
        assertSealedTo(MultiTenantLookupDao.class, MultiTenantCacheableLookupDao.class);
        assertSealedTo(MultiTenantRelationalDao.class, MultiTenantCacheableRelationalDao.class);
        assertSealedTo(LookupDao.class, CacheableLookupDao.class);
        assertSealedTo(RelationalDao.class, CacheableRelationalDao.class);
    }

    private void assertSealedTo(final Class<?> base, final Class<?> permitted) {
        assertTrue(base.isSealed(), base.getSimpleName() + " must be sealed");
        assertEquals(Set.of(permitted), Set.of(base.getPermittedSubclasses()),
                base.getSimpleName() + " permits the wrong subclasses");
    }
}

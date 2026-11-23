package io.appform.dropwizard.sharding;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EncapsulationTest {

    static void assertNoAccessibleConstructors(Class<?> type) {
        for (Constructor<?> ctor : type.getDeclaredConstructors()) {
            final int mod = ctor.getModifiers();
            assertFalse(Modifier.isPublic(mod) || Modifier.isProtected(mod), "Accessible constructor: " + ctor);
        }
    }

    @Test
    void shardCalculatorIsSealedWithHiddenFinalImplementation() throws Exception {
        assertTrue(ShardCalculator.class.isSealed());
        final Class<?>[] permitted = ShardCalculator.class.getPermittedSubclasses();
        assertEquals(1, permitted.length);
        final Class<?> impl = permitted[0];
        assertTrue(Modifier.isFinal(impl.getModifiers()));
        assertFalse(Modifier.isPublic(impl.getModifiers()));
        assertNoAccessibleConstructors(impl);

        final Class<?> factory = Class.forName("io.appform.dropwizard.sharding.ShardCalculators");
        assertFalse(Modifier.isPublic(factory.getModifiers()));
        assertTrue(Modifier.isFinal(factory.getModifiers()));
        for (Constructor<?> ctor : factory.getDeclaredConstructors()) {
            assertTrue(Modifier.isPrivate(ctor.getModifiers()));
        }
    }

    @Test
    void registryCannotBeInstantiatedSubclassedOrMutatedFromOutside() throws Exception {
        assertTrue(Modifier.isFinal(ShardCalculatorRegistry.class.getModifiers()));
        for (Constructor<?> ctor : ShardCalculatorRegistry.class.getDeclaredConstructors()) {
            assertTrue(Modifier.isPrivate(ctor.getModifiers()), ctor.toString());
        }
        for (Method m : ShardCalculatorRegistry.class.getDeclaredMethods()) {
            if (Modifier.isPublic(m.getModifiers()) || Modifier.isProtected(m.getModifiers())) {
                assertTrue(Set.of("get", "contains", "all").contains(m.getName()), "Unexpected accessible method: " + m);
            }
        }
        final Method register = ShardCalculatorRegistry.class.getDeclaredMethod("register", ShardCalculator.class);
        assertFalse(Modifier.isPublic(register.getModifiers()) || Modifier.isProtected(register.getModifiers()));
        final Method clear = ShardCalculatorRegistry.class.getDeclaredMethod("clear");
        assertFalse(Modifier.isPublic(clear.getModifiers()) || Modifier.isProtected(clear.getModifiers()));
    }

    @Test
    void removedTypesAreGone() {
        org.junit.jupiter.api.Assertions.assertThrows(ClassNotFoundException.class,
                () -> Class.forName("io.appform.dropwizard.sharding.dao.ShardedDao"));
        org.junit.jupiter.api.Assertions.assertThrows(ClassNotFoundException.class,
                () -> Class.forName("io.appform.dropwizard.sharding.utils.ShardCalculator"));
    }

    private static final java.util.List<Class<?>> DAO_CLASSES = java.util.List.of(
            io.appform.dropwizard.sharding.dao.MultiTenantLookupDao.class,
            io.appform.dropwizard.sharding.dao.MultiTenantCacheableLookupDao.class,
            io.appform.dropwizard.sharding.dao.MultiTenantRelationalDao.class,
            io.appform.dropwizard.sharding.dao.MultiTenantCacheableRelationalDao.class,
            io.appform.dropwizard.sharding.dao.LookupDao.class,
            io.appform.dropwizard.sharding.dao.CacheableLookupDao.class,
            io.appform.dropwizard.sharding.dao.RelationalDao.class,
            io.appform.dropwizard.sharding.dao.CacheableRelationalDao.class,
            io.appform.dropwizard.sharding.dao.WrapperDao.class);

    @Test
    void daoConstructorsAreNotAccessible() {
        DAO_CLASSES.forEach(EncapsulationTest::assertNoAccessibleConstructors);
    }

    @Test
    void daoHierarchiesAreClosed() {
        assertSealedPermitting(io.appform.dropwizard.sharding.dao.MultiTenantLookupDao.class,
                io.appform.dropwizard.sharding.dao.MultiTenantCacheableLookupDao.class);
        assertSealedPermitting(io.appform.dropwizard.sharding.dao.MultiTenantRelationalDao.class,
                io.appform.dropwizard.sharding.dao.MultiTenantCacheableRelationalDao.class);
        assertSealedPermitting(io.appform.dropwizard.sharding.dao.LookupDao.class,
                io.appform.dropwizard.sharding.dao.CacheableLookupDao.class);
        assertSealedPermitting(io.appform.dropwizard.sharding.dao.RelationalDao.class,
                io.appform.dropwizard.sharding.dao.CacheableRelationalDao.class);
        for (Class<?> leaf : java.util.List.of(
                io.appform.dropwizard.sharding.dao.MultiTenantCacheableLookupDao.class,
                io.appform.dropwizard.sharding.dao.MultiTenantCacheableRelationalDao.class,
                io.appform.dropwizard.sharding.dao.CacheableLookupDao.class,
                io.appform.dropwizard.sharding.dao.CacheableRelationalDao.class,
                io.appform.dropwizard.sharding.dao.WrapperDao.class)) {
            assertTrue(Modifier.isFinal(leaf.getModifiers()), leaf + " must be final");
        }
    }

    @Test
    void bundleInternalsAreNotExposedToSubclasses() throws Exception {
        for (String name : java.util.List.of("bucketResolver", "rootObserver")) {
            final int mod = BundleCommonBase.class.getDeclaredField(name).getModifiers();
            assertFalse(Modifier.isPublic(mod) || Modifier.isProtected(mod), "Accessible field: " + name);
        }
    }

    private static void assertSealedPermitting(Class<?> base, Class<?> onlySubclass) {
        assertTrue(base.isSealed(), base + " must be sealed");
        org.junit.jupiter.api.Assertions.assertArrayEquals(new Class<?>[]{onlySubclass}, base.getPermittedSubclasses());
    }
}

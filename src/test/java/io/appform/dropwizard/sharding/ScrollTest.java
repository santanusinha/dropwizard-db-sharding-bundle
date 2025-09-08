package io.appform.dropwizard.sharding;

import com.google.common.collect.Lists;
import com.google.common.collect.Sets;
import io.appform.dropwizard.sharding.config.ShardingBundleOptions;
import io.appform.dropwizard.sharding.dao.LookupDao;
import io.appform.dropwizard.sharding.dao.MultiTenantLookupDao;
import io.appform.dropwizard.sharding.dao.testdata.entities.ScrollTestEntity;
import io.appform.dropwizard.sharding.observers.internal.TerminalTransactionObserver;
import io.appform.dropwizard.sharding.scroll.ScrollPointer;
import io.appform.dropwizard.sharding.scroll.ScrollResult;
import io.appform.dropwizard.sharding.sharding.BalancedShardManager;
import lombok.extern.slf4j.Slf4j;
import lombok.val;
import org.hibernate.SessionFactory;
import org.hibernate.boot.registry.StandardServiceRegistry;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
import org.hibernate.cfg.Configuration;
import org.hibernate.criterion.DetachedCriteria;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;


/**
 * Tests out functionality for {@link LookupDao#scrollDown(DetachedCriteria, ScrollPointer, int, String)}
 */
@Slf4j
public class ScrollTest {
    private final List<SessionFactory> sessionFactories = Lists.newArrayList();

    private LookupDao<ScrollTestEntity> lookupDao;

    @BeforeEach
    public void before() {
        for (int i = 0; i < 2; i++) {
            sessionFactories.add(buildSessionFactory(String.format("db_%d", i)));
        }
        val shardManager = new BalancedShardManager(sessionFactories.size());
        val shardingOptions = new ShardingBundleOptions();
        val shardInfoProvider = new ShardInfoProvider("default");
        val observer = new TerminalTransactionObserver();
        lookupDao = new LookupDao<>(DBShardingBundleBase.DEFAULT_NAMESPACE,
                new MultiTenantLookupDao<>(Map.of(DBShardingBundleBase.DEFAULT_NAMESPACE, sessionFactories),
                        ScrollTestEntity.class, Map.of(DBShardingBundleBase.DEFAULT_NAMESPACE, shardManager),
                        Map.of(DBShardingBundleBase.DEFAULT_NAMESPACE, shardingOptions),
                        Map.of(DBShardingBundleBase.DEFAULT_NAMESPACE, shardInfoProvider), observer));
    }

    @Test
    public void testScrollUp() {
        val numEntities = 400;
        val pageSize = 10;
        val ids = new HashSet<Integer>();
        val entities = new ArrayList<Integer>();

        populateEntities(1, numEntities, ids);

        var result = (ScrollResult<ScrollTestEntity>) null;
        var pointer = (ScrollPointer) null;

        //Now scroll up to read everything
        do {
            result = lookupDao.scrollUp(DetachedCriteria.forClass(ScrollTestEntity.class),
                                        pointer,
                                        pageSize,
                                        "id");
            addValuesToSet(entities, result);
            pointer = result.getPointer();
            log.info("Received {} entities",
                     result.getResult().stream().map(ScrollTestEntity::getId).collect(Collectors.toList()));
        } while (!result.getResult().isEmpty());
        val expected = new Integer[]{
                399, 395, 394, 392, 400, 391, 398, 390, 397, 387, 396, 386, 393, 382, 389, 380, 388, 379, 385, 378,
                384, 377, 383, 376, 381, 375, 374, 371, 373, 369, 372, 367, 370, 366, 368, 364, 365, 363, 362, 361,
                359, 360, 358, 357, 355, 356, 352, 354, 350, 353, 349, 351, 347, 348, 346, 341, 345, 340, 344, 338,
                343, 332, 342, 331, 339, 328, 337, 327, 336, 326, 335, 325, 334, 320, 333, 317, 330, 316, 329, 315,
                324, 314, 323, 313, 322, 310, 321, 307, 319, 305, 318, 304, 312, 301, 311, 300, 309, 295, 308, 293,
                306, 289, 303, 287, 302, 286, 299, 285, 298, 282, 297, 281, 296, 279, 294, 278, 292, 277, 291, 276,
                290, 273, 288, 270, 284, 269, 283, 268, 280, 267, 275, 261, 274, 260, 272, 259, 271, 258, 266, 257,
                265, 255, 264, 253, 263, 251, 262, 250, 256, 247, 254, 246, 252, 245, 249, 244, 248, 243, 242, 241,
                240, 239, 238, 235, 237, 234, 236, 233, 228, 232, 225, 231, 223, 230, 222, 229, 221, 227, 218, 226,
                217, 224, 213, 220, 212, 219, 210, 216, 208, 215, 207, 214, 206, 211, 205, 209, 204, 203, 202, 198,
                201, 197, 200, 195, 199, 191, 196, 189, 194, 187, 193, 186, 192, 184, 190, 181, 188, 177, 185, 176,
                183, 172, 182, 166, 180, 164, 179, 160, 178, 159, 175, 158, 174, 157, 173, 154, 171, 152, 170, 151,
                169, 150, 168, 149, 167, 147, 165, 146, 163, 145, 162, 144, 161, 143, 156, 141, 155, 134, 153, 131,
                148, 130, 142, 129, 140, 128, 139, 127, 138, 125, 137, 124, 136, 121, 135, 120, 133, 119, 132, 118,
                126, 117, 123, 116, 122, 115, 113, 114, 111, 112, 109, 110, 108, 106, 107, 102, 105, 98, 104, 97, 103
                , 96, 101, 95, 100, 94, 99, 93, 92, 91, 90, 86, 89, 85, 88, 84, 87, 83, 81, 82, 80, 79, 77, 78, 76,
                75, 74, 72, 73, 69, 71, 68, 70, 67, 65, 66, 64, 62, 63, 61, 58, 60, 57, 59, 56, 54, 55, 51, 53, 49,
                52, 47, 50, 46, 48, 45, 43, 44, 40, 42, 39, 41, 38, 37, 35, 36, 33, 34, 30, 32, 26, 31, 25, 29, 24,
                28, 23, 27, 21, 22, 20, 18, 19, 17, 15, 16, 11, 14, 10, 13, 9, 12, 8, 7, 6, 5, 2, 4, 1, 3};
        final List<Integer> expectedSortedValues = Arrays.asList(expected);
        assertEquals(expectedSortedValues, entities, "Seems like sort order is broken");
        assertTrue(entities.containsAll(ids),
                   "There are " + Sets.difference(ids, Set.copyOf(entities)) + " ids missing in scroll");
    }

    @Test
    public void testScrollDown() {
        val numEntities = 400;
        val pageSize = 10;
        val ids = new HashSet<Integer>();
        val entities = new ArrayList<Integer>();

        populateEntities(1, numEntities, ids);

        var result = (ScrollResult<ScrollTestEntity>) null;
        var pointer = (ScrollPointer) null;

        //Now scroll up to read everything
        do {
            result = lookupDao.scrollDown(DetachedCriteria.forClass(ScrollTestEntity.class),
                                          pointer,
                                          pageSize,
                                          "id");
            addValuesToSet(entities, result);
            pointer = result.getPointer();
            log.info("Received {} entities",
                     result.getResult().stream().map(ScrollTestEntity::getId).collect(Collectors.toList()));
        } while (!result.getResult().isEmpty());
        val expected = new Integer[]{
                1, 3, 2, 4, 6, 5, 8, 7, 9, 12, 10, 13, 11, 14, 15, 16, 19, 17, 20, 18, 21, 22, 23, 27, 24, 28, 25, 29
                , 26, 31, 30, 32, 33, 34, 35, 36, 38, 37, 39, 41, 40, 42, 43, 44, 48, 45, 50, 46, 52, 47, 53, 49, 55,
                51, 56, 54, 57, 59, 58, 60, 63, 61, 64, 62, 65, 66, 70, 67, 71, 68, 73, 69, 74, 72, 76, 75, 77, 78,
                80, 79, 81, 82, 87, 83, 88, 84, 89, 85, 90, 86, 92, 91, 99, 93, 100, 94, 101, 95, 103, 96, 104, 97,
                105, 98, 107, 102, 108, 106, 109, 110, 111, 112, 113, 114, 122, 115, 123, 116, 126, 117, 132, 118,
                133, 119, 135, 120, 136, 121, 137, 124, 138, 125, 139, 127, 140, 128, 142, 129, 148, 130, 153, 131,
                155, 134, 156, 141, 161, 143, 162, 144, 163, 145, 165, 146, 167, 147, 168, 149, 169, 150, 170, 151,
                171, 152, 173, 154, 174, 157, 175, 158, 178, 159, 179, 160, 180, 164, 182, 166, 183, 172, 185, 176,
                188, 177, 190, 181, 192, 184, 193, 186, 194, 187, 196, 189, 199, 191, 200, 195, 201, 197, 202, 198,
                204, 203, 205, 209, 206, 211, 207, 214, 208, 215, 210, 216, 212, 219, 213, 220, 217, 224, 218, 226,
                221, 227, 222, 229, 223, 230, 225, 231, 228, 232, 236, 233, 237, 234, 238, 235, 240, 239, 242, 241,
                248, 243, 249, 244, 252, 245, 254, 246, 256, 247, 262, 250, 263, 251, 264, 253, 265, 255, 266, 257,
                271, 258, 272, 259, 274, 260, 275, 261, 280, 267, 283, 268, 284, 269, 288, 270, 290, 273, 291, 276,
                292, 277, 294, 278, 296, 279, 297, 281, 298, 282, 299, 285, 302, 286, 303, 287, 306, 289, 308, 293,
                309, 295, 311, 300, 312, 301, 318, 304, 319, 305, 321, 307, 322, 310, 323, 313, 324, 314, 329, 315,
                330, 316, 333, 317, 334, 320, 335, 325, 336, 326, 337, 327, 339, 328, 342, 331, 343, 332, 344, 338,
                345, 340, 346, 341, 347, 348, 349, 351, 350, 353, 352, 354, 355, 356, 358, 357, 359, 360, 362, 361,
                365, 363, 368, 364, 370, 366, 372, 367, 373, 369, 374, 371, 381, 375, 383, 376, 384, 377, 385, 378,
                388, 379, 389, 380, 393, 382, 396, 386, 397, 387, 398, 390, 400, 391, 392, 394, 395, 399};
        final List<Integer> expectedSortedValues = Arrays.asList(expected);
        assertEquals(expectedSortedValues, entities, "Looks like sort order is broken");
        assertTrue(entities.containsAll(ids),
                   "There are " + Sets.difference(ids, Set.copyOf(entities)) + " ids missing in scroll");
    }

    @Test
    public void testScrollSorted() {
        val numEntities = 400;
        val ids = new HashSet<Integer>();
        val entities = new HashSet<Integer>();

        populateEntities(1, numEntities, ids);
        var result = (ScrollResult<ScrollTestEntity>) null;
        var pointer = (ScrollPointer) null;
        do {
            result = lookupDao.scrollDown(DetachedCriteria.forClass(ScrollTestEntity.class),
                                          pointer,
                                          10,
                                          "id");
            addValuesToSet(entities, result);
            pointer = result.getPointer();
            log.info("Received {} entities",
                     result.getResult().stream().map(ScrollTestEntity::getId).collect(Collectors.toList()));
        } while (result.getResult().size() != 0);
        assertTrue(entities.containsAll(ids),
                   "There are " + Sets.difference(ids, entities).size() + " ids missing in scroll");
        populateEntities(numEntities + 1, 2 * numEntities, ids);

        /*Pointer does not need to be reset here. This is because sort order is on the auto increment id field.
        If it is not such a field, the results of scroll would be wrong for obvious reasons*/

        do {
            result = lookupDao.scrollDown(DetachedCriteria.forClass(ScrollTestEntity.class),
                                          pointer,
                                          10,
                                          "id");
            addValuesToSet(entities, result);
            pointer = result.getPointer();
            log.info("Received {} entities", result.getResult().size());
        } while (result.getResult().size() != 0);
        assertTrue(entities.containsAll(ids),
                   "There are " + Sets.difference(ids, entities).size() + " ids missing in scroll");
    }

    @Test
    public void testScrollUpWithQuerySpec() {
        val numEntities = 400;
        val pageSize = 10;
        val ids = new HashSet<Integer>();
        val entities = new ArrayList<Integer>();

        populateEntities(1, numEntities, ids);

        var result = (ScrollResult<ScrollTestEntity>) null;
        var pointer = (ScrollPointer) null;

        //Now scroll up to read everything
        do {
            result = lookupDao.scrollUp((queryRoot, query, criteriaBuilder) -> {

            },
                    pointer,
                    pageSize,
                    "id");
            addValuesToSet(entities, result);
            pointer = result.getPointer();
            log.info("Received {} entities",
                    result.getResult().stream().map(ScrollTestEntity::getId).collect(Collectors.toList()));
        } while (!result.getResult().isEmpty());
        val expected = new Integer[]{
                399, 395, 394, 392, 400, 391, 398, 390, 397, 387, 396, 386, 393, 382, 389, 380, 388, 379, 385, 378,
                384, 377, 383, 376, 381, 375, 374, 371, 373, 369, 372, 367, 370, 366, 368, 364, 365, 363, 362, 361,
                359, 360, 358, 357, 355, 356, 352, 354, 350, 353, 349, 351, 347, 348, 346, 341, 345, 340, 344, 338,
                343, 332, 342, 331, 339, 328, 337, 327, 336, 326, 335, 325, 334, 320, 333, 317, 330, 316, 329, 315,
                324, 314, 323, 313, 322, 310, 321, 307, 319, 305, 318, 304, 312, 301, 311, 300, 309, 295, 308, 293,
                306, 289, 303, 287, 302, 286, 299, 285, 298, 282, 297, 281, 296, 279, 294, 278, 292, 277, 291, 276,
                290, 273, 288, 270, 284, 269, 283, 268, 280, 267, 275, 261, 274, 260, 272, 259, 271, 258, 266, 257,
                265, 255, 264, 253, 263, 251, 262, 250, 256, 247, 254, 246, 252, 245, 249, 244, 248, 243, 242, 241,
                240, 239, 238, 235, 237, 234, 236, 233, 228, 232, 225, 231, 223, 230, 222, 229, 221, 227, 218, 226,
                217, 224, 213, 220, 212, 219, 210, 216, 208, 215, 207, 214, 206, 211, 205, 209, 204, 203, 202, 198,
                201, 197, 200, 195, 199, 191, 196, 189, 194, 187, 193, 186, 192, 184, 190, 181, 188, 177, 185, 176,
                183, 172, 182, 166, 180, 164, 179, 160, 178, 159, 175, 158, 174, 157, 173, 154, 171, 152, 170, 151,
                169, 150, 168, 149, 167, 147, 165, 146, 163, 145, 162, 144, 161, 143, 156, 141, 155, 134, 153, 131,
                148, 130, 142, 129, 140, 128, 139, 127, 138, 125, 137, 124, 136, 121, 135, 120, 133, 119, 132, 118,
                126, 117, 123, 116, 122, 115, 113, 114, 111, 112, 109, 110, 108, 106, 107, 102, 105, 98, 104, 97, 103
                , 96, 101, 95, 100, 94, 99, 93, 92, 91, 90, 86, 89, 85, 88, 84, 87, 83, 81, 82, 80, 79, 77, 78, 76,
                75, 74, 72, 73, 69, 71, 68, 70, 67, 65, 66, 64, 62, 63, 61, 58, 60, 57, 59, 56, 54, 55, 51, 53, 49,
                52, 47, 50, 46, 48, 45, 43, 44, 40, 42, 39, 41, 38, 37, 35, 36, 33, 34, 30, 32, 26, 31, 25, 29, 24,
                28, 23, 27, 21, 22, 20, 18, 19, 17, 15, 16, 11, 14, 10, 13, 9, 12, 8, 7, 6, 5, 2, 4, 1, 3};
        final List<Integer> expectedSortedValues = Arrays.asList(expected);
        assertEquals(expectedSortedValues, entities, "Seems like sort order is broken");
        assertTrue(entities.containsAll(ids),
                "There are " + Sets.difference(ids, Set.copyOf(entities)) + " ids missing in scroll");
    }

    @Test
    public void testScrollDownWithQuerySpec() {
        val numEntities = 400;
        val pageSize = 10;
        val ids = new HashSet<Integer>();
        val entities = new ArrayList<Integer>();

        populateEntities(1, numEntities, ids);

        var result = (ScrollResult<ScrollTestEntity>) null;
        var pointer = (ScrollPointer) null;

        //Now scroll up to read everything
        do {
            result = lookupDao.scrollDown((queryRoot, query, criteriaBuilder) -> {

            },
                    pointer,
                    pageSize,
                    "id");
            addValuesToSet(entities, result);
            pointer = result.getPointer();
            log.info("Received {} entities",
                    result.getResult().stream().map(ScrollTestEntity::getId).collect(Collectors.toList()));
        } while (!result.getResult().isEmpty());
        val expected = new Integer[]{
                1, 3, 2, 4, 6, 5, 8, 7, 9, 12, 10, 13, 11, 14, 15, 16, 19, 17, 20, 18, 21, 22, 23, 27, 24, 28, 25, 29
                , 26, 31, 30, 32, 33, 34, 35, 36, 38, 37, 39, 41, 40, 42, 43, 44, 48, 45, 50, 46, 52, 47, 53, 49, 55,
                51, 56, 54, 57, 59, 58, 60, 63, 61, 64, 62, 65, 66, 70, 67, 71, 68, 73, 69, 74, 72, 76, 75, 77, 78,
                80, 79, 81, 82, 87, 83, 88, 84, 89, 85, 90, 86, 92, 91, 99, 93, 100, 94, 101, 95, 103, 96, 104, 97,
                105, 98, 107, 102, 108, 106, 109, 110, 111, 112, 113, 114, 122, 115, 123, 116, 126, 117, 132, 118,
                133, 119, 135, 120, 136, 121, 137, 124, 138, 125, 139, 127, 140, 128, 142, 129, 148, 130, 153, 131,
                155, 134, 156, 141, 161, 143, 162, 144, 163, 145, 165, 146, 167, 147, 168, 149, 169, 150, 170, 151,
                171, 152, 173, 154, 174, 157, 175, 158, 178, 159, 179, 160, 180, 164, 182, 166, 183, 172, 185, 176,
                188, 177, 190, 181, 192, 184, 193, 186, 194, 187, 196, 189, 199, 191, 200, 195, 201, 197, 202, 198,
                204, 203, 205, 209, 206, 211, 207, 214, 208, 215, 210, 216, 212, 219, 213, 220, 217, 224, 218, 226,
                221, 227, 222, 229, 223, 230, 225, 231, 228, 232, 236, 233, 237, 234, 238, 235, 240, 239, 242, 241,
                248, 243, 249, 244, 252, 245, 254, 246, 256, 247, 262, 250, 263, 251, 264, 253, 265, 255, 266, 257,
                271, 258, 272, 259, 274, 260, 275, 261, 280, 267, 283, 268, 284, 269, 288, 270, 290, 273, 291, 276,
                292, 277, 294, 278, 296, 279, 297, 281, 298, 282, 299, 285, 302, 286, 303, 287, 306, 289, 308, 293,
                309, 295, 311, 300, 312, 301, 318, 304, 319, 305, 321, 307, 322, 310, 323, 313, 324, 314, 329, 315,
                330, 316, 333, 317, 334, 320, 335, 325, 336, 326, 337, 327, 339, 328, 342, 331, 343, 332, 344, 338,
                345, 340, 346, 341, 347, 348, 349, 351, 350, 353, 352, 354, 355, 356, 358, 357, 359, 360, 362, 361,
                365, 363, 368, 364, 370, 366, 372, 367, 373, 369, 374, 371, 381, 375, 383, 376, 384, 377, 385, 378,
                388, 379, 389, 380, 393, 382, 396, 386, 397, 387, 398, 390, 400, 391, 392, 394, 395, 399};
        final List<Integer> expectedSortedValues = Arrays.asList(expected);
        assertEquals(expectedSortedValues, entities, "Looks like sort order is broken");
        assertTrue(entities.containsAll(ids),
                "There are " + Sets.difference(ids, Set.copyOf(entities)) + " ids missing in scroll");
    }

    @Test
    public void testScrollSortedWithQuerySpec() {
        val numEntities = 400;
        val ids = new HashSet<Integer>();
        val entities = new HashSet<Integer>();

        populateEntities(1, numEntities, ids);
        var result = (ScrollResult<ScrollTestEntity>) null;
        var pointer = (ScrollPointer) null;
        do {
            result = lookupDao.scrollDown((queryRoot, query, criteriaBuilder) -> {

            },
                    pointer,
                    10,
                    "id");
            addValuesToSet(entities, result);
            pointer = result.getPointer();
            log.info("Received {} entities",
                    result.getResult().stream().map(ScrollTestEntity::getId).collect(Collectors.toList()));
        } while (result.getResult().size() != 0);
        assertTrue(entities.containsAll(ids),
                "There are " + Sets.difference(ids, entities).size() + " ids missing in scroll");
        populateEntities(numEntities + 1, 2 * numEntities, ids);

        /*Pointer does not need to be reset here. This is because sort order is on the auto increment id field.
        If it is not such a field, the results of scroll would be wrong for obvious reasons*/

        do {
            result = lookupDao.scrollDown((queryRoot, query, criteriaBuilder) -> {

            },
                    pointer,
                    10,
                    "id");
            addValuesToSet(entities, result);
            pointer = result.getPointer();
            log.info("Received {} entities", result.getResult().size());
        } while (result.getResult().size() != 0);
        assertTrue(entities.containsAll(ids),
                "There are " + Sets.difference(ids, entities).size() + " ids missing in scroll");
    }


    private static void addValuesToSet(Collection<Integer> entities, ScrollResult<ScrollTestEntity> result) {
        result.getResult().stream().map(ScrollTestEntity::getValue).forEach(entities::add);
    }

    private SessionFactory buildSessionFactory(String dbName) {
        Configuration configuration = new Configuration();
        configuration.setProperty("hibernate.dialect",
                                  "org.hibernate.dialect.H2Dialect");
        configuration.setProperty("hibernate.connection.driver_class",
                                  "org.h2.Driver");
        configuration.setProperty("hibernate.connection.url", "jdbc:h2:mem:" + dbName);
        configuration.setProperty("hibernate.hbm2ddl.auto", "create");
        configuration.setProperty("hibernate.current_session_context_class", "managed");
        configuration.setProperty("hibernate.show_sql", "true");
        configuration.setProperty("hibernate.format_sql", "true");
        configuration.addAnnotatedClass(ScrollTestEntity.class);

        StandardServiceRegistry serviceRegistry
                = new StandardServiceRegistryBuilder().applySettings(
                        configuration.getProperties())
                .build();
        return configuration.buildSessionFactory(serviceRegistry);
    }

    private void populateEntities(int start, int end, HashSet<Integer> ids) {
        ids.addAll(IntStream.rangeClosed(start, end)
                           .mapToObj(i -> {
                               try {
                                   return lookupDao.save(new ScrollTestEntity(0, "ID_" + i, i))
                                           .orElse(null)
                                           ;
                               }
                               catch (Exception e) {
                                   throw new RuntimeException(e);
                               }
                           })
                           .filter(Objects::nonNull)
                           .map(ScrollTestEntity::getValue)
                           .collect(Collectors.toSet()));
    }
}

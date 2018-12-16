package in.cleartax.dropwizard.sharding.test.sampleapp;

/**
 * Created by mohitsingh on 10/12/18.
 */

import org.h2.store.fs.FileUtils;
import org.h2.tools.Backup;
import org.h2.tools.DeleteDbFiles;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;

/**
 * This sample application shows how to create and use a read-only database in a
 * zip file. The database file is split into multiple smaller files, to speed up
 * random-access. Splitting up the file is only needed if the database file is
 * larger than a few megabytes.
 */
public class PrepareReadOnlyTestDB {

    // TODO: We can get rid of hardcoding of filepath.
    public static void generateReplicaDB() throws Exception {

        // delete all files in this directory
        FileUtils.deleteRecursive("/tmp/testReadOnly", false);


        Connection conn;
        Class.forName("org.h2.Driver");

        // create a database where the database file is split into
        // multiple small files, 4 MB each (2^22). The larger the
        // parts, the faster opening the database, but also the
        // more files. 4 MB seems to be a good compromise, so
        // the prefix split:22: is used, which means each part is
        // 2^22 bytes long
        conn = DriverManager.getConnection(
                "jdbc:h2:split:22:/tmp/testReadOnly/test");

        System.out.println("adding test data...");
        Statement stat = conn.createStatement();
        stat.execute("DROP TABLE IF EXISTS `orders`;\n" +
                "CREATE TABLE `orders` (\n" +
                "  `id` bigint(20) unsigned NOT NULL AUTO_INCREMENT,\n" +
                "  `order_ext_id` varchar(255) DEFAULT NULL,\n" +
                "  `customer_id` varchar(255) DEFAULT NULL,\n" +
                "  `amount` int(11) DEFAULT NULL,\n" +
                "  PRIMARY KEY (`id`)\n" +
                ");");

        stat.execute("DROP TABLE IF EXISTS `order_items`;\n" +
                "CREATE TABLE `order_items` (\n" +
                "  `id` bigint(20) unsigned NOT NULL AUTO_INCREMENT,\n" +
                "  `name` varchar(255) DEFAULT NULL,\n" +
                "  `order_id` int(11) DEFAULT NULL,\n" +
                "  PRIMARY KEY (`id`)\n" +
                ");");
        stat.execute("TRUNCATE TABLE `orders`;\n" +
                "TRUNCATE TABLE `order_items`;\n" +
                "INSERT INTO `orders` (`id`, `order_ext_id`, `customer_id`, `amount`) VALUES ('1', '11111111-2222-3333-4444-aaaaaaaaaaa1', 1, 10000);\n" +
                "INSERT INTO `order_items` (`id`, `name`, `order_id`) VALUES ('0', 'test', '10');\n" +
                "INSERT INTO `order_items` (`id`, `name`, `order_id`) VALUES ('1', 'test', '10');");

        System.out.println("defrag to reduce random access...");
        stat.execute("shutdown defrag");
        conn.close();

        System.out.println("create the zip file...");
        Backup.execute("/tmp/testReadOnly/test.zip", "/tmp/testReadOnly", "", true);

        // delete the old database files
        DeleteDbFiles.execute("split:/tmp/testReadOnly", "test", true);
    }

}
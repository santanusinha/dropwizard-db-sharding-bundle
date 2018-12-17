package in.cleartax.dropwizard.sharding.test.sampleapp;

/**
 * Created by mohitsingh on 10/12/18.
 */

import lombok.extern.slf4j.Slf4j;
import org.h2.store.fs.FileUtils;
import org.h2.tools.Backup;
import org.h2.tools.DeleteDbFiles;

import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.DriverManager;

/**
 * This sample application shows how to create and use a read-only database in a
 * zip file. The database file is split into multiple smaller files, to speed up
 * random-access. Splitting up the file is only needed if the database file is
 * larger than a few megabytes.
 */
@Slf4j
public class PrepareReadOnlyTestDB {

    public static final String TEMP_DIR_PATH = System.getProperty("java.io.tmpdir");
    public static final String TEMP_DB_SUBDIRECTORY = "readOnly";
    public static final String TEMP_DB_NAME = "readonly_test";

    public static String generateReplicaDB() throws Exception {

        // delete all files in this directory
        String readOnlyDbDirectoryPath = Paths.get(TEMP_DIR_PATH, TEMP_DB_SUBDIRECTORY).toString();
        String readOnlyDbFile = Paths.get(readOnlyDbDirectoryPath, TEMP_DB_NAME).toString();
        String readOnlyDbFileZip = readOnlyDbFile + ".zip";
        FileUtils.deleteRecursive(readOnlyDbDirectoryPath, false);


        Connection conn;
        Class.forName("org.h2.Driver");

        // create a database where the database file is split into
        // multiple small files, 4 MB each (2^22). The larger the
        // parts, the faster opening the database, but also the
        // more files. 4 MB seems to be a good compromise, so
        // the prefix split:22: is used, which means each part is
        // 2^22 bytes long
        conn = DriverManager.getConnection(
                "jdbc:h2:split:22:" + readOnlyDbFile);

        log.info("adding test data...");
        //Statement stat = conn.createStatement();
        try {
            TestHelper.initDb("init_read_replica.sql", conn);
            log.info("create the zip file...");
            Backup.execute(readOnlyDbFileZip, readOnlyDbDirectoryPath, "", true);
            String readOnlyDBUrl = "jdbc:h2:split:zip:" + readOnlyDbFileZip + "!/" + TEMP_DB_NAME + ";MODE=MySQL;DATABASE_TO_UPPER=false;IGNORECASE=TRUE;";

            // delete the old database files
            DeleteDbFiles.execute("split:"+readOnlyDbDirectoryPath, TEMP_DB_NAME, true);
            return readOnlyDBUrl;
        }
        catch (Exception e) {
            log.debug("Unable to create readOnly DB");
        }
        finally {
            conn.close();
        }
        return null;
    }

}
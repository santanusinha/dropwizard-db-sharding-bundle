package in.cleartax.dropwizard.sharding.test.sampleapp;

/**
 * Created by mohitsingh on 10/12/18.
 */

import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.tuple.ImmutablePair;
import org.apache.commons.lang3.tuple.Pair;
import org.h2.store.fs.FileUtils;
import org.h2.tools.Backup;
import org.h2.tools.DeleteDbFiles;

import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.List;

/**
 * This sample application shows how to create and use a read-only database in a
 * zip file. The database file is split into multiple smaller files, to speed up
 * random-access. Splitting up the file is only needed if the database file is
 * larger than a few megabytes.
 */
@Slf4j
public class PrepareReadOnlyTestDB {

    static final String TEMP_DIR_PATH = System.getProperty("java.io.tmpdir");
    static final String TEMP_DB_SUBDIRECTORY = "readOnly";

    public static void generateReplicaDB(List<Pair<String, String>> dbNameAndMigrationFileName) throws Exception {
        String readOnlyDbDirectoryPath = getReadOnlyDbDirectoryPath();
        FileUtils.deleteRecursive(readOnlyDbDirectoryPath, false);
        for (Pair<String, String> pair : dbNameAndMigrationFileName) {
            generateReplicaDB(pair.getKey(), pair.getValue(), readOnlyDbDirectoryPath);
        }
    }

    private static void generateReplicaDB(String dbName, String migrationFileName,
                                          String readOnlyDbDirectoryPath) throws Exception {

        String readOnlyDbFile = getReadOnlyDbFile(readOnlyDbDirectoryPath, dbName);
        String readOnlyDbFileZip = getReadOnlyDbFileZip(readOnlyDbFile);


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

        try {
            log.info("adding test data...");
            TestHelper.initDb(migrationFileName, conn);
            log.info("create the zip file...");
            Backup.execute(readOnlyDbFileZip, readOnlyDbDirectoryPath, "", true);
            // delete the old database files
            DeleteDbFiles.execute("split:" + readOnlyDbDirectoryPath, dbName, true);
        } catch (Exception e) {
            log.debug("Unable to create readOnly DB", e);
        } finally {
            conn.close();
        }
    }

    public static String getReplicaDbUrl(String dbName) {
        String readOnlyDbDirectoryPath = getReadOnlyDbDirectoryPath();
        String readOnlyDbFile = getReadOnlyDbFile(readOnlyDbDirectoryPath, dbName);
        String readOnlyDbFileZip = getReadOnlyDbFileZip(readOnlyDbFile);
        return "jdbc:h2:split:zip:" + readOnlyDbFileZip + "!/" + dbName +
                ";MODE=MySQL;DATABASE_TO_UPPER=false;IGNORECASE=TRUE;";
    }

    private static String getReadOnlyDbDirectoryPath() {
        return Paths.get(TEMP_DIR_PATH, TEMP_DB_SUBDIRECTORY).toString();
    }

    private static String getReadOnlyDbFile(String readOnlyDbDirectoryPath, String dbName) {
        return Paths.get(readOnlyDbDirectoryPath, dbName).toString();
    }

    private static String getReadOnlyDbFileZip(String readOnlyDbFile) {
        return readOnlyDbFile + ".zip";
    }
}
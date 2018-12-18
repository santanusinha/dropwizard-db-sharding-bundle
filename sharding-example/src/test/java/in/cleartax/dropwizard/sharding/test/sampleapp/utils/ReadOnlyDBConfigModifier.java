package in.cleartax.dropwizard.sharding.test.sampleapp.utils;

import io.dropwizard.testing.ResourceHelpers;
import lombok.extern.slf4j.Slf4j;

import java.io.FileNotFoundException;
import java.io.PrintWriter;

import static in.cleartax.dropwizard.sharding.test.sampleapp.PrepareReadOnlyTestDB.generateReplicaDB;

/**
 * Created by mohitsingh on 17/12/18.
 */
@Slf4j
public class ReadOnlyDBConfigModifier {

    public static String modifyReadOnlyDBConfig() {

        String readOnlyDbUrl = null;
        try{
            readOnlyDbUrl = generateReplicaDB();
        } catch (Exception e) {
            e.printStackTrace();
        }
        String testYamlFilePath = ResourceHelpers
                .resourceFilePath("test_with_replica.yml");

        Object yamlObject = null;
        try {
            yamlObject = YamlHelpers.getMapFromYaml(testYamlFilePath);
        } catch (FileNotFoundException e) {
            e.printStackTrace();
        }

        String yamlString = YamlHelpers.getYamlString(yamlObject);
        String replacedString = yamlString.replace("READ_ONLY_DB_URL", readOnlyDbUrl);

        try (PrintWriter out = new PrintWriter(testYamlFilePath)) {
            out.println(replacedString);
        } catch (FileNotFoundException e) {
            e.printStackTrace();
        }

        return testYamlFilePath;
    }
}
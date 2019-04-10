package in.cleartax.dropwizard.sharding.test.sampleapp.utils;

import io.dropwizard.testing.ResourceHelpers;
import lombok.extern.slf4j.Slf4j;

import java.io.FileNotFoundException;
import java.io.PrintWriter;
import java.util.Map;

import static in.cleartax.dropwizard.sharding.test.sampleapp.PrepareReadOnlyTestDB.getReplicaDbUrl;

/**
 * Created by mohitsingh on 17/12/18.
 */
@Slf4j
public class ReadOnlyDBConfigModifier {

    public static String modifyReadOnlyDBConfig(String ymlConfigFile,
                                                Map<String, String> targetVarToSubstituteAndDbName) {
        String ymlFilePath = ResourceHelpers.resourceFilePath(ymlConfigFile);
        for (Map.Entry<String, String> entry : targetVarToSubstituteAndDbName.entrySet()) {
            modifyReadOnlyDBConfig(ResourceHelpers.resourceFilePath(ymlConfigFile), entry.getKey(), entry.getValue());
        }
        return ymlFilePath;
    }

    private static void modifyReadOnlyDBConfig(String ymlFilePath, String targetVarToSubstitute,
                                               String dbName) {

        String readOnlyDbUrl;
        try {
            readOnlyDbUrl = getReplicaDbUrl(dbName);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        Object yamlObject = null;
        try {
            yamlObject = YamlHelpers.getMapFromYaml(ymlFilePath);
        } catch (FileNotFoundException e) {
            e.printStackTrace();
        }

        String yamlString = YamlHelpers.getYamlString(yamlObject);
        assert yamlString != null;
        String replacedString = yamlString.replace(targetVarToSubstitute, readOnlyDbUrl);

        try (PrintWriter out = new PrintWriter(ymlFilePath)) {
            out.println(replacedString);
        } catch (FileNotFoundException e) {
            e.printStackTrace();
        }
    }
}
package in.cleartax.dropwizard.sharding.test.sampleapp.utils;

import org.yaml.snakeyaml.Yaml;

import java.io.*;
import java.util.LinkedHashMap;

/**
 * Created by mohitsingh on 17/12/18.
 */
public class YamlHelpers {
    public static LinkedHashMap getMapFromYaml(String filePath) throws FileNotFoundException {
        try (InputStream input = new FileInputStream(new File(filePath))) {
            Yaml yaml = new Yaml();
            return (LinkedHashMap) yaml.load(input);
        } catch (IOException e) {
            return null;
        }
    }

    public static void writeYamlToFile(String filePath, Object object) throws IOException {
        Yaml yaml = new Yaml();
        try (FileWriter fileWriter = new FileWriter(filePath)) {
            yaml.dump(object, fileWriter);
        }
    }

    public static String getYamlString(Object object) {
        Yaml yaml = new Yaml();
        try (StringWriter stringWriter = new StringWriter()) {
            yaml.dump(object, stringWriter);
            return stringWriter.toString();
        } catch (IOException e) {
            return null;
        }
    }
}
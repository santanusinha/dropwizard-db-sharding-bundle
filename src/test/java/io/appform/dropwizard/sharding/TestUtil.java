package io.appform.dropwizard.sharding;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class TestUtil {

    public static <T> Map<Integer, List<T>> convertToMap(List<T> input){
        return input.stream().collect(Collectors.groupingBy(input::indexOf));
    }

}

package io.dropwizard.sharding.dao.testdata.transformation;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * @author tushar.naik
 * @version 1.0  20/01/18 - 8:01 PM
 */
@Builder
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Identity {
    private String name;
    private Address address;
    private int age;
}

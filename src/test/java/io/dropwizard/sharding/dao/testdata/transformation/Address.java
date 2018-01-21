package io.dropwizard.sharding.dao.testdata.transformation;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * @author tushar.naik
 * @version 1.0  20/01/18 - 8:04 PM
 */
@Builder
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Address {
    private String street;
    private String location;
}

package io.dropwizard.sharding.dao.testdata;

import io.dropwizard.sharding.dao.testdata.transformation.Address;
import io.dropwizard.sharding.dao.testdata.transformation.Identity;
import io.dropwizard.sharding.dao.testdata.transformation.StateCensus;

/**
 * @author tushar.naik
 * @version 1.0  21/01/18 - 6:35 PM
 */
public class ModelHelper {

    public static StateCensus sampleStateCensus() {
        return sampleStateCensus("ssn1");
    }

    public static StateCensus sampleStateCensus(String ssn) {
        return StateCensus.builder()
                          .identity(sampleIdentity())
                          .ssn(ssn)
                          .active(true)
                          .stateName("karnataka")
                          .build();
    }

    public static Identity sampleIdentity() {
        return Identity.builder()
                       .name("Ram")
                       .address(new Address("3rd street", "bangalore"))
                       .age(10).build();
    }
}

package io.appform.dropwizard.sharding.config;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import javax.validation.Valid;
import javax.validation.constraints.NotNull;

/**
 * @author - suraj.s
 * @version - 1.0
 * @since - 11-03-2021
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DatabaseWarmUpConfig {

    @Valid
    private boolean warmUpRequired = false;

    @NotNull
    private String validationQuery = "SELECT 1"; // try using complex SQL query.

    @Valid
    private int numberOfIteration = 3;

    @Valid
    private int numberOfThreads = 10;

    @Valid
    private long sleepDurationInMillis = 100;
}

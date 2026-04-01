package io.appform.dropwizard.sharding.metrics;

import com.codahale.metrics.Meter;
import com.codahale.metrics.Timer;
import lombok.Builder;
import lombok.Value;

@Value
@Builder
public class MetricData {
    Meter total;
    Meter success;
    Meter failed;
    Timer timer;
}

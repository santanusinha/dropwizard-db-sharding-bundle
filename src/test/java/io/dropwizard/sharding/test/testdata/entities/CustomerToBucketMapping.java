package io.dropwizard.sharding.test.testdata.entities;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import javax.persistence.*;

/**
 * Created on 03/10/18
 */
@Entity
@Table(name = "customer_bucket")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class CustomerToBucketMapping {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private long id;

    @Column(name = "customer_id")
    private String customerId;

    @Column(name = "bucket_id")
    private String bucketId;
}

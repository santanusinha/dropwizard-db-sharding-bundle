package io.dropwizard.sharding.utils.entities;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import javax.persistence.*;

/**
 * Created on 03/10/18
 */
@Entity
@Table(name = "bucket_shard", uniqueConstraints = {
        @UniqueConstraint(name = "uidx_bucketidshardid_bucketshardmapping", columnNames = {"bucket_id", "shard_id"})
})
@Data
@NoArgsConstructor
@AllArgsConstructor
public class BucketToShardMapping {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private long id;

    @Column(name = "bucket_id")
    private String bucketId;
    @Column(name = "shard_id")
    private String shardId;
}

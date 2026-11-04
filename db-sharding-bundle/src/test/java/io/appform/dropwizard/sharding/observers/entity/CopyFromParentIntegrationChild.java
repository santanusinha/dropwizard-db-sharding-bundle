package io.appform.dropwizard.sharding.observers.entity;

import io.appform.dropwizard.sharding.sharding.BucketKey;
import io.appform.dropwizard.sharding.sharding.CopyFromParent;
import io.appform.dropwizard.sharding.sharding.ParentEntity;
import io.appform.dropwizard.sharding.sharding.ShardingKey;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;
import lombok.experimental.FieldNameConstants;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.GeneratedValue;
import javax.persistence.GenerationType;
import javax.persistence.Id;
import javax.persistence.Table;

/**
 * Related (child) entity for {@link CopyFromParentIntegrationParent}.
 * <p>
 * The {@code @ShardingKey} field ({@code parentRef}) is itself the field that gets populated by
 * {@code @CopyFromParent} from the parent's lookup key, rather than being set explicitly by
 * calling code. This is deliberate: it lets tests prove that field copying (performed by
 * {@link io.appform.dropwizard.sharding.observers.CopyFromParentObserver}) always completes
 * before bucket-key computation (performed by the framework's built-in
 * {@link io.appform.dropwizard.sharding.observers.bucket.BucketKeyObserver}), which reads this
 * same sharding key field to compute {@link #bucketKey}. If copy ran after bucket calculation,
 * {@code parentRef} would still be {@code null} at bucket-key computation time, which would fail
 * with an NPE in the underlying hashing call.
 */
@Entity
@Table(name = "cfp_integration_child")
@Getter
@Setter
@ToString
@NoArgsConstructor
@FieldNameConstants
@ParentEntity(CopyFromParentIntegrationParent.class)
public class CopyFromParentIntegrationChild {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private long id;

    @Column
    @ShardingKey
    @CopyFromParent(field = "name")
    private String parentRef;

    @Column
    @CopyFromParent(field = "transactionRef")
    private String txnRef;

    @Column
    @BucketKey
    private int bucketKey;

    @Column(name = "`value`")
    private String value;
}

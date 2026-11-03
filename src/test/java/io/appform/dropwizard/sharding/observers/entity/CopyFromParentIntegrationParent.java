package io.appform.dropwizard.sharding.observers.entity;

import io.appform.dropwizard.sharding.sharding.LookupKey;
import lombok.AllArgsConstructor;
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
 * Lookup (parent) entity used to verify, end to end through a real transaction/observer chain
 * and a real database, that {@code @CopyFromParent} field copying and bucket-key computation
 * for a related {@link CopyFromParentIntegrationChild} happen in the correct order (copy, then
 * bucket calculation, then save) - and that a {@code copyIfDefaultOnly} violation rolls back the
 * whole {@link io.appform.dropwizard.sharding.dao.LockedContext} transaction.
 */
@Entity
@Table(name = "cfp_integration_parent")
@Getter
@Setter
@ToString
@NoArgsConstructor
@AllArgsConstructor
@FieldNameConstants
public class CopyFromParentIntegrationParent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private long id;

    @Column
    @LookupKey
    private String name;

    @Column
    private String transactionRef;
}

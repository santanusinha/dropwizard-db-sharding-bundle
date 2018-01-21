package io.dropwizard.sharding.dao.testdata.transformation;

import io.dropwizard.sharding.dao.TransformationBase;
import io.dropwizard.sharding.sharding.LookupKey;
import io.dropwizard.sharding.sharding.TransformedField;
import lombok.*;

import javax.persistence.*;

/**
 * @author tushar.naik
 * @version 1.0  20/01/18 - 8:02 PM
 */
@AllArgsConstructor
@Entity
@Table(name = "state_census")
@NoArgsConstructor
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
@Builder
public class StateCensus extends TransformationBase<byte[], String> {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private long id;

    @LookupKey
    @Column(name = "ssn")
    private String ssn;

    @TransformedField
    @Transient
    private Identity identity;

    @Column(name = "active")
    private boolean active;

    @Column(name = "state_name")
    private String stateName;
}

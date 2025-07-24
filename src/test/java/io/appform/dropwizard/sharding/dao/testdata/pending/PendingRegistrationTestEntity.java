package io.appform.dropwizard.sharding.dao.testdata.pending;

import io.appform.dropwizard.sharding.sharding.LookupKey;
import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.Id;
import javax.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.validator.constraints.NotEmpty;

@Entity
@Table(name = "pending_registration_test_entity")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PendingRegistrationTestEntity {

    @Id
    @NotEmpty
    @LookupKey
    @Column(name = "ext_id", unique = true)
    private String externalId;

    @Column(name = "text", nullable = false)
    @NotEmpty
    private String text;
}

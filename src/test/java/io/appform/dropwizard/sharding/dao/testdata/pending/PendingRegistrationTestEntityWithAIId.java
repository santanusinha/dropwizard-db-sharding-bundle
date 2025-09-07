package io.appform.dropwizard.sharding.dao.testdata.pending;

import io.appform.dropwizard.sharding.sharding.LookupKey;
import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.GeneratedValue;
import javax.persistence.GenerationType;
import javax.persistence.Id;
import javax.persistence.Table;
import javax.validation.constraints.NotEmpty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "pending_registration_test_entity_with_ai_id")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PendingRegistrationTestEntityWithAIId {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private long id;

    @NotEmpty
    @LookupKey
    @Column(name = "ext_id", unique = true)
    private String externalId;

    @Column(name = "text", nullable = false)
    @NotEmpty
    private String text;
}

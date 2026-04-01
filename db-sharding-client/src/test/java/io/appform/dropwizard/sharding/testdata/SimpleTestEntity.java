package io.appform.dropwizard.sharding.testdata;

import io.appform.dropwizard.sharding.sharding.LookupKey;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.Id;
import javax.persistence.Table;

@Entity
@Table(name = "simple_entity")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SimpleTestEntity {

    @Id
    @LookupKey
    @Column(name = "ext_id", unique = true)
    private String externalId;

    @Column(name = "text_value")
    private String value;
}


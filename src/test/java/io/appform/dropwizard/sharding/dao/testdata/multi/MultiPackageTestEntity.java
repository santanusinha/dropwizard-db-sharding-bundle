package io.appform.dropwizard.sharding.dao.testdata.multi;

import io.appform.dropwizard.sharding.sharding.LookupKey;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.ToString;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "audits")
@Data
@NoArgsConstructor
@AllArgsConstructor
@ToString
@Builder
public class MultiPackageTestEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private long id;

    @LookupKey
    @Column(name = "lookup")
    private String lookup;

    @Column(name = "text")
    private String text;

}


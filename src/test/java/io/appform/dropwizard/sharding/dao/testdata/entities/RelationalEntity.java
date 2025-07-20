package io.appform.dropwizard.sharding.dao.testdata.entities;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.NamedQueries;
import jakarta.persistence.NamedQuery;
import jakarta.persistence.Table;

@Entity
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Table(name = "relations")
@NamedQueries({
        @NamedQuery(name = "testUpdateUsingKeyTwo", query = "update RelationalEntity set value = :value where keyTwo =:keyTwo")})
public class RelationalEntity {

    @Id
    @Column(name = "key", nullable = false, unique = true)
    private String key;

    private String keyTwo;

    private String value;

}

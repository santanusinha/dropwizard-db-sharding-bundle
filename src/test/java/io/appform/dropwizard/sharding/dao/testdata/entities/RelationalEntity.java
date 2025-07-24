package io.appform.dropwizard.sharding.dao.testdata.entities;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.Id;
import javax.persistence.NamedQueries;
import javax.persistence.NamedQuery;
import javax.persistence.Table;

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
    @Column(name = "`key`", nullable = false, unique = true)
    private String key;

    private String keyTwo;

    @Column(name = "`value`")
    private String value;

}

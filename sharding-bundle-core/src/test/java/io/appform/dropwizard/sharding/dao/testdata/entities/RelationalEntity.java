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
        @NamedQuery(name = "testUpdateUsingKeyTwo", query = "update RelationalEntity set val = :val where key_two =:keyTwo")})
public class RelationalEntity {

    @Id
    @Column(name = "key_one", nullable = false, unique = true)
    private String keyOne;

    @Column(name = "key_two")
    private String keyTwo;

    private String val;

}

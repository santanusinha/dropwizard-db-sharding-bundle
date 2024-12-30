package io.appform.dropwizard.sharding.dao.testdata.entities;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.GeneratedValue;
import javax.persistence.GenerationType;
import javax.persistence.Id;
import javax.persistence.Table;

@Entity
@Data
@NoArgsConstructor
@AllArgsConstructor
@Table(name = "relations")
public class RelationalEntityWithAIKey {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    long id;

    @Column(name = "key_one", nullable = false)
    private String keyOne;

    @Column(name = "val")
    private String val;

    @Builder
    public RelationalEntityWithAIKey(String keyOne, String val) {
        this.keyOne = keyOne;
        this.val = val;
    }
}

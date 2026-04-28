package io.appform.dropwizard.sharding.dao.testdata.entities;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;



@Entity
@Data
@NoArgsConstructor
@AllArgsConstructor
@Table(name = "relations")
public class RelationalEntityWithAIKey {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    long id;

    @Column(name = "`key`", nullable = false)
    private String key;

    @Column(name = "`value`")
    private String value;

    @Builder
    public RelationalEntityWithAIKey(String key, String value) {
        this.key = key;
        this.value = value;
    }
}

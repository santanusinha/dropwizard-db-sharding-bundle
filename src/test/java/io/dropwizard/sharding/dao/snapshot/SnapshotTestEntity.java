package io.dropwizard.sharding.dao.snapshot;

import io.dropwizard.sharding.sharding.LookupKey;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.Id;
import javax.persistence.Table;

@Entity
@Table(name = "snapshots")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SnapshotTestEntity {

    @Id
    @LookupKey
    private String id;

    @Column
    private String data;

}

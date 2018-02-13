package io.dropwizard.sharding.dao.snapshot;

import lombok.Getter;
import lombok.Setter;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.Table;

@Table(name = "snapshot_test")
@Entity
@Getter
@Setter
public class SnapshotEntityImpl extends SnapshotEntity {

    @Column(name = "data")
    private byte[] data;

}

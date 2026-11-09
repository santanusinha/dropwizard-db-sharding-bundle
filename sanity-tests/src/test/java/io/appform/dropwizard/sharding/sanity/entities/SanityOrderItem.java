package io.appform.dropwizard.sharding.sanity.entities;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.GeneratedValue;
import javax.persistence.GenerationType;
import javax.persistence.Id;
import javax.persistence.Table;
import javax.persistence.UniqueConstraint;

/**
 * Child/relational entity used with {@link io.appform.dropwizard.sharding.dao.RelationalDao}.
 * Shard routing is determined by the {@code orderId} parent key passed to
 * {@code RelationalDao.save(parentKey, entity)} — NOT by an annotation on this entity.
 *
 * <p>The {@code itemName} column has a unique constraint to enable testing rollback
 * on constraint violations.
 */
@Entity
@Table(name = "sanity_order_items",
        uniqueConstraints = @UniqueConstraint(columnNames = {"order_id", "item_name"}))
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SanityOrderItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "order_id", nullable = false)
    private String orderId;

    @Column(name = "item_name", nullable = false)
    private String itemName;

    @Column(name = "quantity")
    private int quantity;

    @Column(name = "price")
    private int price;

    @Column(name = "updated_at", insertable = false, updatable = false,
            columnDefinition = "TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP")
    private java.sql.Timestamp updatedAt;
}

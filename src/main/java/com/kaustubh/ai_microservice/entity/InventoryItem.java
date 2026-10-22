package com.kaustubh.ai_microservice.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.math.BigDecimal;

@Entity
@Table(name = "inventory_items", schema = "public")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class InventoryItem {

    @Id
    @Column(name = "sku", length = 50, nullable = false)
    private String sku;

    @Column(name = "item_name", length = 150, nullable = false)
    private String itemName;

    @Column(name = "current_stock", nullable = false)
    private Integer currentStock;

    @Column(name = "reorder_threshold", nullable = false)
    private Integer reorderThreshold;

    @Column(name = "unit_price_inr", precision = 12, scale = 2, nullable = false)
    private BigDecimal unitPriceInr;

    @Column(name = "preferred_vendor_id", length = 50, nullable = false)
    private String preferredVendorId;

    public InventoryItem(String itemName, BigDecimal unitPriceInr) {
        this.itemName = itemName;
        this.unitPriceInr = unitPriceInr;
    }
}

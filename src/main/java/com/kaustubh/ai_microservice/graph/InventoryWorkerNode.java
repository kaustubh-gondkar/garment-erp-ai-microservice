//package com.kaustubh.ai_microservice.graph;
//
//
//import com.kaustubh.ai_microservice.graph.P2pGraphModels.InventoryNodeOutput;
//import org.apache.logging.log4j.LogManager;
//import org.apache.logging.log4j.Logger;
//import org.springframework.dao.EmptyResultDataAccessException;
//import org.springframework.jdbc.core.JdbcTemplate;
//import org.springframework.stereotype.Component;
//
//import java.math.BigDecimal;
//
//@Component
//public class InventoryWorkerNode {
//
//    private static final Logger log = LogManager.getLogger(InventoryWorkerNode.class);
//    private static final BigDecimal HITL_THRESHOLD = new BigDecimal("50000.00");
//
//    private final JdbcTemplate jdbcTemplate;
//
//    public InventoryWorkerNode(JdbcTemplate jdbcTemplate) {
//        this.jdbcTemplate = jdbcTemplate;
//    }
//
//    public InventoryNodeOutput verifyCatalogAndStock(String skuCode, int requestedQuantity) {
//        if (skuCode == null || skuCode.isBlank()) {
//            log.warn("InventoryWorkerNode received null/blank SKU code. Returning empty state.");
//            return new InventoryNodeOutput("UNKNOWN_SKU", 0, 0, BigDecimal.ZERO, BigDecimal.ZERO, false);
//        }
//
//        // Adjust column/table names if your Day 1 schema differs slightly
//        String sql = """
//            SELECT sku, current_stock, reorder_threshold, unit_price_inr
//            FROM inventory_items
//            WHERE sku = ?
//            """;
//
//        try {
//            return jdbcTemplate.queryForObject(sql, (rs, rowNum) -> {
//                String sku = rs.getString("sku");
//                int stock = rs.getInt("current_stock");
//                int threshold = rs.getInt("reorder_threshold");
//                BigDecimal unitPrice = rs.getBigDecimal("unit_price_inr");
//
//                int safeQty = Math.max(requestedQuantity, 0);
//                BigDecimal estimatedTotal = unitPrice.multiply(BigDecimal.valueOf(safeQty));
//                boolean requiresHitl = estimatedTotal.compareTo(HITL_THRESHOLD) > 0;
//
//                log.info("InventoryNode completed [Thread={}]: SKU={}, Stock={}, UnitPrice={}, EstTotal={}, HITL={}",
//                        Thread.currentThread(), sku, stock, unitPrice, estimatedTotal, requiresHitl);
//
//                return new InventoryNodeOutput(sku, stock, threshold, unitPrice, estimatedTotal, requiresHitl);
//            }, skuCode.trim().toUpperCase());
//
//        } catch (EmptyResultDataAccessException ex) {
//            log.warn("SKU {} not found in inventory_items table.", skuCode);
//            return new InventoryNodeOutput(skuCode, 0, 0, BigDecimal.ZERO, BigDecimal.ZERO, false);
//        }
//    }
//}
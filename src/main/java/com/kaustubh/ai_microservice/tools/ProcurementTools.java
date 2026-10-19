package com.kaustubh.ai_microservice.tools;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Component
public class ProcurementTools {

  private static final Logger log = LoggerFactory.getLogger(ProcurementTools.class);
  private static final BigDecimal HITL_THRESHOLD_INR = new BigDecimal("50000.00");

  private final JdbcTemplate jdbcTemplate;

  public ProcurementTools(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  public record InventoryStatus(
      String sku,
      String itemName,
      int currentPhysicalStock,
      int inFlightPoQuantity,
      int reorderThreshold,
      int netDeficitToOrder,
      BigDecimal unitPriceInr,
      String preferredVendorId,
      boolean needsReorder) {}

  public record PurchaseOrderResult(
      UUID poId,
      String sku,
      int quantity,
      BigDecimal totalAmountInr,
      String status,
      String message) {}

  @Tool(
      description =
          "Check real-time physical stock, in-flight open PO quantities, and exact net deficit to order for a SKU or all items.")
  public List<InventoryStatus> checkInventoryLevels(
      @ToolParam(
              description =
                  "Optional SKU code. Pass null or empty string to audit all inventory items.")
          String sku) {

    log.info("[TOOL CALL] checkInventoryLevels invoked with sku={}", sku);

    String baseSql =
        """
                SELECT
                    i.sku, i.item_name, i.current_stock, i.reorder_threshold,
                    i.unit_price_inr, i.preferred_vendor_id,
                    COALESCE(SUM(p.quantity), 0) AS in_flight_qty
                FROM inventory_items i
                LEFT JOIN purchase_orders p
                    ON i.sku = p.sku
                    AND p.status IN ('AUTO_APPROVED', 'PENDING_MANAGER_APPROVAL')
                """;

    boolean hasSku = (sku != null && !sku.isBlank());
    String finalSql =
        hasSku
            ? baseSql
                + " WHERE i.sku = ? GROUP BY i.sku, i.item_name, i.current_stock, i.reorder_threshold, i.unit_price_inr, i.preferred_vendor_id"
            : baseSql
                + " GROUP BY i.sku, i.item_name, i.current_stock, i.reorder_threshold, i.unit_price_inr, i.preferred_vendor_id";

    Object[] params = hasSku ? new Object[] {sku.trim()} : new Object[] {};

    return jdbcTemplate.query(
        finalSql,
        (rs, rowNum) -> {
          int physical = rs.getInt("current_stock");
          int inFlight = rs.getInt("in_flight_qty");
          int threshold = rs.getInt("reorder_threshold");
          int netDeficit = Math.max(0, threshold - (physical + inFlight));

          return new InventoryStatus(
              rs.getString("sku"),
              rs.getString("item_name"),
              physical,
              inFlight,
              threshold,
              netDeficit,
              rs.getBigDecimal("unit_price_inr"),
              rs.getString("preferred_vendor_id"),
              netDeficit > 0);
        },
        params);
  }

  @Transactional
  @Tool(
      description =
          "Create a Purchase Order (PO) for the net deficit of a SKU. Enforces in-flight idempotency and the ₹50,000 HITL approval gate.")
  public PurchaseOrderResult createPurchaseOrder(
      @ToolParam(description = "The exact SKU code to order") String sku,
      @ToolParam(description = "Positive integer net deficit quantity to order") int quantity) {

    log.info("[TOOL CALL] createPurchaseOrder invoked for sku={}, quantity={}", sku, quantity);

    if (quantity <= 0) {
      throw new IllegalArgumentException("Order quantity must be greater than zero.");
    }

    // 1. Fetch physical stock, unit price, vendor, AND current in-flight open PO quantity
    Map<String, Object> item =
        jdbcTemplate.queryForMap(
            """
                SELECT
                    i.current_stock, i.reorder_threshold, i.unit_price_inr, i.preferred_vendor_id,
                    COALESCE(SUM(p.quantity), 0) AS in_flight_qty
                FROM inventory_items i
                LEFT JOIN purchase_orders p
                    ON i.sku = p.sku
                    AND p.status IN ('AUTO_APPROVED', 'PENDING_MANAGER_APPROVAL')
                WHERE i.sku = ?
                GROUP BY i.current_stock, i.reorder_threshold, i.unit_price_inr, i.preferred_vendor_id
                """,
            sku.trim());

    int physicalStock = ((Number) item.get("current_stock")).intValue();
    int inFlightQty = ((Number) item.get("in_flight_qty")).intValue();
    int threshold = ((Number) item.get("reorder_threshold")).intValue();
    int actualNetDeficit = Math.max(0, threshold - (physicalStock + inFlightQty));

    // 2. State-Based Idempotency Shield: If open POs already cover the deficit, block duplicate
    // retry!
    if (actualNetDeficit == 0) {
      log.warn(
          "[IDEMPOTENCY SHIELD] Deficit for {} already covered by in-flight POs (inFlight={})",
          sku,
          inFlightQty);
      return new PurchaseOrderResult(
          null,
          sku.trim(),
          0,
          BigDecimal.ZERO,
          "SKIPPED_ALREADY_IN_FLIGHT",
          "No PO created: Stock deficit is already covered by existing open Purchase Orders ("
              + inFlightQty
              + " units in flight).");
    }

    // Clamp quantity to actualNetDeficit if LLM tried to over-order
    int safeQuantity = Math.min(quantity, actualNetDeficit);

    BigDecimal unitPrice = (BigDecimal) item.get("unit_price_inr");
    String vendorId = (String) item.get("preferred_vendor_id");
    BigDecimal totalAmount = unitPrice.multiply(BigDecimal.valueOf(safeQuantity));

    String status =
        totalAmount.compareTo(HITL_THRESHOLD_INR) > 0
            ? "PENDING_MANAGER_APPROVAL"
            : "AUTO_APPROVED";

    UUID poId = UUID.randomUUID();
    // Unique key per stock state transition
    String idempotencyKey =
        "PO-%s-STOCK%d-INFLIGHT%d-%s"
            .formatted(sku.trim(), physicalStock, inFlightQty, poId.toString().substring(0, 8));

    jdbcTemplate.update(
        "INSERT INTO purchase_orders (po_id, sku, vendor_id, quantity, total_amount_inr, status, idempotency_key) VALUES (?, ?, ?, ?, ?, ?, ?)",
        poId,
        sku.trim(),
        vendorId,
        safeQuantity,
        totalAmount,
        status,
        idempotencyKey);

    String message =
        status.equals("PENDING_MANAGER_APPROVAL")
            ? "PO exceeds ₹50,000 HITL threshold. Routed to Manager Approval Queue."
            : "PO is within ₹50,000 limit and has been AUTO_APPROVED for dispatch.";

    return new PurchaseOrderResult(poId, sku.trim(), safeQuantity, totalAmount, status, message);
  }
}

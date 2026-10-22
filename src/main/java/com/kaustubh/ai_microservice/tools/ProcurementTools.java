package com.kaustubh.ai_microservice.tools;

import com.kaustubh.ai_microservice.entity.InventoryItem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Isolation;
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
    @Tool(description = "Creates a purchase order after validating SKU against master inventory and calculating authoritative pricing from the database.")
    @Transactional
    public String createPurchaseOrder(String sku, String vendorId, int quantity, BigDecimal userProvidedAmountInr) {
        try {
            log.info("[TOOL CALL] createPurchaseOrder requested for sku={}, vendor={}, quantity={}", sku, vendorId, quantity);

            // 1. AUTHORITATIVE CATALOG LOOKUP (Prevent prompt price/SKU spoofing)
            String catalogSql = "SELECT item_name, unit_price_inr FROM inventory_items WHERE sku = ?";
            List<InventoryItem> items = jdbcTemplate.query(
                    catalogSql,
                    (rs, rowNum) -> new InventoryItem(rs.getString("item_name"), rs.getBigDecimal("unit_price_inr")),
                    sku
            );

            if (items.isEmpty()) {
                log.warn("[CATALOG_VIOLATION] Attempted PO creation for non-existent SKU: {}", sku);
                return "ERROR: SKU '" + sku + "' does not exist in the master inventory catalog. PO creation aborted.";
            }

            InventoryItem catalogItem = items.get(0);

            // 2. CALCULATE AUTHORITATIVE TOTAL (Ignore prompt price manipulation)
            BigDecimal authoritativeTotal = catalogItem.getUnitPriceInr().multiply(BigDecimal.valueOf(quantity));

            if (userProvidedAmountInr != null && userProvidedAmountInr.compareTo(authoritativeTotal) != 0) {
                log.warn("[PRICE_MISMATCH] Prompt stated INR {}, but catalog price for {} units of {} is INR {}. Enforcing DB catalog pricing.",
                        userProvidedAmountInr, quantity, sku, authoritativeTotal);
            }

            // 3. DETERMINE APPROVAL GATE (> INR 50,000 threshold)
            boolean requiresApproval = authoritativeTotal.compareTo(new BigDecimal("50000")) > 0;
            String status = requiresApproval ? "PENDING_MANAGER_APPROVAL" : "AUTO_APPROVED";

            UUID poId = UUID.randomUUID();
            String toolIdempotencyKey = String.format("PO-%s-%s-%s-%s", sku, vendorId, quantity, UUID.randomUUID().toString().substring(0, 8));

            // 4. INSERT INTO PURCHASE ORDERS WITH AUTHORITATIVE DATA
            String insertSql = """
                INSERT INTO purchase_orders (po_id, sku, vendor_id, quantity, total_amount_inr, status, idempotency_key)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                RETURNING po_id
                """;

            UUID insertedPoId = jdbcTemplate.queryForObject(
                    insertSql,
                    UUID.class,
                    poId,
                    sku,
                    vendorId,
                    quantity,
                    authoritativeTotal, // Bound to DB price
                    status,
                    toolIdempotencyKey
            );

            log.info("[DB_SUCCESS] Secure PO created. ID: {}, SKU: {}, Total: INR {}, Status: {}",
                    insertedPoId, sku, authoritativeTotal, status);

            if (requiresApproval) {
                return String.format(
                        "AWAITING_HUMAN_APPROVAL: Purchase order %s created for %d units of %s (%s). Total: INR %s. Exceeds INR 50,000 threshold, requires manager approval.",
                        insertedPoId, quantity, catalogItem.getItemName(), sku, authoritativeTotal
                );
            } else {
                return String.format(
                        "SUCCESS: Purchase order %s created for %d units of %s (%s). Total: INR %s. Status: AUTO_APPROVED.",
                        insertedPoId, quantity, catalogItem.getItemName(), sku, authoritativeTotal
                );
            }

        } catch (Exception e) {
            log.error("[TOOL_ERROR] Failed to execute secure createPurchaseOrder", e);
            return "Error creating purchase order: " + e.getMessage();
        }
    }
    /**
     * Confirms and transitions a high-value PO (> 50k INR) from PENDING_MANAGER_APPROVAL to APPROVED.
     * Enforces pessimistic row locking (SELECT ... FOR UPDATE) to prevent race conditions
     * during concurrent manager approval submissions.
     *
     * @param poId The UUID string representing the target purchase order.
     * @param approverId The corporate identity/employee ID of the approving authority.
     * @return Deterministic structured execution message.
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public String confirmHighValuePurchaseOrder(UUID poId, String approverId) {
        log.info("[HITL_CONFIRMATION] Initiating manual approval for poId: {} by approver: {}", poId, approverId);

        // 1. Pessimistic lock row to avoid double-approval race conditions
        String selectSql = """
            SELECT status, total_amount_inr, sku, vendor_id 
            FROM purchase_orders 
            WHERE po_id = ? 
            FOR UPDATE
            """;

        PoRecord po;
        try {
            po = jdbcTemplate.queryForObject(selectSql, (rs, rowNum) -> new PoRecord(
                    rs.getString("status"),
                    rs.getBigDecimal("total_amount_inr"),
                    rs.getString("sku"),
                    rs.getString("vendor_id")
            ), poId);
        } catch (EmptyResultDataAccessException e) {
            log.error("[HITL_CONFIRMATION] Purchase order not found: {}", poId);
            throw new IllegalArgumentException("Purchase order " + poId + " does not exist.");
        }

        // 2. Strict State Machine Validation
        if ("APPROVED".equalsIgnoreCase(po.status()) || "AUTO_APPROVED".equalsIgnoreCase(po.status())) {
            log.warn("[HITL_CONFIRMATION] PO {} is already approved. Idempotent return.", poId);
            return String.format("PO %s is already approved.", poId);
        }

        if (!"PENDING_MANAGER_APPROVAL".equalsIgnoreCase(po.status())) {
            log.error("[HITL_CONFIRMATION] Invalid state transition: PO {} is in status {}", poId, po.status());
            throw new IllegalStateException(
                    String.format("Cannot approve PO %s because current status is %s (expected PENDING_MANAGER_APPROVAL).",
                            poId, po.status())
            );
        }

        // 3. Atomic State Mutation
        String updateSql = """
            UPDATE purchase_orders 
            SET status = 'APPROVED'
            WHERE po_id = ?
            """;

        int rowsUpdated = jdbcTemplate.update(updateSql, poId);
        if (rowsUpdated != 1) {
            throw new IllegalStateException("Failed to update status for PO " + poId);
        }

        log.info("[HITL_CONFIRMATION] PO {} successfully approved by {}. SKU: {}, Amount: INR {}",
                poId, approverId, po.sku(), po.totalAmount());

        return String.format(
                "PO %s for SKU %s (INR %s) successfully APPROVED by %s. Downstream ERP dispatch initiated.",
                poId, po.sku(), po.totalAmount(), approverId
        );
    }

    private record PoRecord(String status, java.math.BigDecimal totalAmount, String sku, String vendorId) {}

}

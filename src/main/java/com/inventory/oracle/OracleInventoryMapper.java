package com.inventory.oracle;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * Low-level Oracle statements. Callers must use one transaction for each whole
 * posting, verify affected-row counts, and acquire all locks in deterministic
 * order. This mapper alone does not guarantee ledger/balance consistency.
 */
@Mapper
public interface OracleInventoryMapper {
    @Insert("INSERT INTO ir_orders (id, status) VALUES (#{id}, 'CONFIRMED')")
    int insertOrder(@Param("id") String id);

    @Insert("""
        INSERT INTO ir_order_lines (id, order_id, warehouse_id, sku, quantity, unit_price)
        VALUES (#{id}, #{orderId}, #{warehouse}, #{sku}, #{quantity}, #{unitPrice})
        """)
    int insertOrderLine(@Param("id") String id, @Param("orderId") String orderId,
        @Param("warehouse") String warehouse, @Param("sku") String sku,
        @Param("quantity") BigDecimal quantity, @Param("unitPrice") BigDecimal unitPrice);

    @Insert("INSERT INTO ir_shipments (id, order_id, status) VALUES (#{id}, #{orderId}, 'DRAFT')")
    int insertShipment(@Param("id") String id, @Param("orderId") String orderId);

    @Insert("""
        INSERT INTO ir_shipment_lines (id, shipment_id, order_id, order_line_id, warehouse_id,
          sku, quantity, unit_price, amount)
        VALUES (#{id}, #{shipmentId}, #{orderId}, #{orderLineId}, #{warehouse}, #{sku},
          #{quantity}, #{unitPrice}, #{amount})
        """)
    int insertShipmentLine(@Param("id") String id, @Param("shipmentId") String shipmentId,
        @Param("orderId") String orderId, @Param("orderLineId") String orderLineId,
        @Param("warehouse") String warehouse, @Param("sku") String sku,
        @Param("quantity") BigDecimal quantity, @Param("unitPrice") BigDecimal unitPrice,
        @Param("amount") BigDecimal amount);

    @Select("SELECT id, order_id, status FROM ir_shipments WHERE id = #{id} FOR UPDATE")
    Map<String, Object> lockShipment(@Param("id") String id);

    @Update("UPDATE ir_shipments SET status = 'CONFIRMED' WHERE id = #{id} AND status = 'DRAFT'")
    int confirmShipment(@Param("id") String id);

    @Update("""
        UPDATE ir_shipments SET status = 'POSTED', posted_at = SYSTIMESTAMP
        WHERE id = #{id} AND status = 'CONFIRMED'
        """)
    int postShipment(@Param("id") String id);

    @Select("""
        SELECT id, shipment_id, order_line_id, warehouse_id, sku, quantity, unit_price,
               amount, returned_quantity, returned_amount
        FROM ir_shipment_lines WHERE id = #{id} FOR UPDATE
        """)
    Map<String, Object> lockShipmentLine(@Param("id") String id);

    /** Must be called only after locking the referenced posted shipment line. */
    @Update("""
        UPDATE ir_shipment_lines SET returned_quantity = returned_quantity + #{quantity},
          returned_amount = returned_amount + #{amount}
        WHERE id = #{id} AND #{quantity} > 0 AND #{amount} >= 0
          AND returned_quantity + #{quantity} <= quantity
          AND returned_amount + #{amount} <= amount
        """)
    int reserveReturn(@Param("id") String id, @Param("quantity") BigDecimal quantity,
        @Param("amount") BigDecimal amount);

    @Insert("INSERT INTO ir_returns (id, shipment_id, status) VALUES (#{id}, #{shipmentId}, 'DRAFT')")
    int insertReturn(@Param("id") String id, @Param("shipmentId") String shipmentId);

    @Insert("""
        INSERT INTO ir_return_lines (id, return_id, shipment_id, shipment_line_id, quantity, amount)
        VALUES (#{id}, #{returnId}, #{shipmentId}, #{shipmentLineId}, #{quantity}, #{amount})
        """)
    int insertReturnLine(@Param("id") String id, @Param("returnId") String returnId,
        @Param("shipmentId") String shipmentId, @Param("shipmentLineId") String shipmentLineId,
        @Param("quantity") BigDecimal quantity, @Param("amount") BigDecimal amount);

    @Update("UPDATE ir_returns SET status = 'CONFIRMED' WHERE id = #{id} AND status = 'DRAFT'")
    int confirmReturn(@Param("id") String id);

    @Update("UPDATE ir_returns SET status = 'POSTED', posted_at = SYSTIMESTAMP WHERE id = #{id} AND status = 'CONFIRMED'")
    int postReturn(@Param("id") String id);

    /** Initial provisioning is an explicit operation, not a concurrent posting path. */
    @Insert("INSERT INTO ir_stock (warehouse_id, sku, quantity) VALUES (#{warehouse}, #{sku}, 0)")
    int insertStock(@Param("warehouse") String warehouse, @Param("sku") String sku);

    /** An absent balance is not locked. Provision rows before beginning postings. */
    @Select("SELECT quantity FROM ir_stock WHERE warehouse_id = #{warehouse} AND sku = #{sku} FOR UPDATE")
    BigDecimal lockStock(@Param("warehouse") String warehouse, @Param("sku") String sku);

    @Update("""
        UPDATE ir_stock SET quantity = quantity - #{quantity}
        WHERE warehouse_id = #{warehouse} AND sku = #{sku} AND #{quantity} > 0 AND quantity >= #{quantity}
        """)
    int decrementStock(@Param("warehouse") String warehouse, @Param("sku") String sku,
        @Param("quantity") BigDecimal quantity);

    @Update("""
        UPDATE ir_stock SET quantity = quantity + #{quantity}
        WHERE warehouse_id = #{warehouse} AND sku = #{sku} AND #{quantity} > 0
        """)
    int incrementStock(@Param("warehouse") String warehouse, @Param("sku") String sku,
        @Param("quantity") BigDecimal quantity);

    @Insert("""
        INSERT INTO ir_stock_movements (id, document_type, document_id, document_line_id,
          warehouse_id, sku, quantity_delta)
        VALUES (#{id}, #{type}, #{documentId}, #{lineId}, #{warehouse}, #{sku}, #{delta})
        """)
    int insertMovement(@Param("id") String id, @Param("type") String type,
        @Param("documentId") String documentId, @Param("lineId") String lineId,
        @Param("warehouse") String warehouse, @Param("sku") String sku, @Param("delta") BigDecimal delta);

    @Insert("""
        INSERT INTO ir_journal_entries (id, document_type, document_id, document_line_id, account, amount)
        VALUES (#{id}, #{type}, #{documentId}, #{lineId}, #{account}, #{amount})
        """)
    int insertJournal(@Param("id") String id, @Param("type") String type,
        @Param("documentId") String documentId, @Param("lineId") String lineId,
        @Param("account") String account, @Param("amount") BigDecimal amount);

    @Insert("""
        INSERT INTO ir_request_keys (request_key, fingerprint, document_type, document_id)
        VALUES (#{key}, #{fingerprint}, #{type}, #{documentId})
        """)
    int insertRequest(@Param("key") String key, @Param("fingerprint") String fingerprint,
        @Param("type") String type, @Param("documentId") String documentId);

    @Select("SELECT fingerprint, document_type, document_id FROM ir_request_keys WHERE request_key = #{key}")
    Map<String, Object> findRequest(@Param("key") String key);

    @Insert("""
        INSERT INTO ir_outbox (id, document_type, document_id, payload)
        VALUES (#{id}, #{type}, #{documentId}, #{payload})
        """)
    int insertOutbox(@Param("id") String id, @Param("type") String type,
        @Param("documentId") String documentId, @Param("payload") String payload);

    @Insert("""
        INSERT INTO ir_action_audit (id, actor, action, document_type, document_id)
        VALUES (#{id}, #{actor}, #{action}, #{type}, #{documentId})
        """)
    int insertActionAudit(@Param("id") String id, @Param("actor") String actor,
        @Param("action") String action, @Param("type") String type, @Param("documentId") String documentId);

    @Select("""
        SELECT s.warehouse_id, s.sku, s.quantity AS balance,
               NVL(SUM(m.quantity_delta), 0) AS ledger_quantity
        FROM ir_stock s LEFT JOIN ir_stock_movements m
          ON m.warehouse_id = s.warehouse_id AND m.sku = s.sku
        GROUP BY s.warehouse_id, s.sku, s.quantity
        HAVING s.quantity <> NVL(SUM(m.quantity_delta), 0)
        """)
    List<Map<String, Object>> findStockDiscrepancies();
}

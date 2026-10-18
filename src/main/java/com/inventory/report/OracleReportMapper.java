package com.inventory.report;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** Each query is a single Oracle statement, with statement-level read consistency. */
@Mapper
public interface OracleReportMapper {
    @Select("""
        SELECT s.warehouse_id, s.sku, s.quantity AS balance,
               NVL(SUM(m.quantity_delta), 0) AS ledger_quantity,
               s.quantity - NVL(SUM(m.quantity_delta), 0) AS difference
        FROM ir_stock s LEFT JOIN ir_stock_movements m
          ON m.warehouse_id = s.warehouse_id AND m.sku = s.sku
        WHERE s.warehouse_id = #{warehouse} AND s.sku = #{sku}
        GROUP BY s.warehouse_id, s.sku, s.quantity
        """)
    Map<String, Object> stock(@Param("warehouse") String warehouse, @Param("sku") String sku);

    /* Period is based on document posted_at, not journal created_at. Orphan entries are
       separately counted by their own created_at because they have no posted document. */
    @Select("""
        WITH documents AS (
          SELECT 'SHIPMENT' AS document_type, s.id AS document_id, NVL(SUM(l.amount), 0) AS amount
          FROM ir_shipments s LEFT JOIN ir_shipment_lines l ON l.shipment_id = s.id
          WHERE s.status = 'POSTED' AND s.posted_at >= #{from} AND s.posted_at < #{to}
          GROUP BY s.id
          UNION ALL
          SELECT 'RETURN', r.id, -NVL(SUM(l.amount), 0)
          FROM ir_returns r LEFT JOIN ir_return_lines l ON l.return_id = r.id
          WHERE r.status = 'POSTED' AND r.posted_at >= #{from} AND r.posted_at < #{to}
          GROUP BY r.id
        ), journal AS (
          SELECT document_type, document_id,
                 SUM(CASE WHEN account = 'RECEIVABLE' THEN amount ELSE 0 END) AS receivable,
                 SUM(CASE WHEN account = 'REVENUE' THEN amount ELSE 0 END) AS revenue_debit,
                 COUNT(CASE WHEN account = 'RECEIVABLE' THEN 1 END) AS receivable_count,
                 COUNT(CASE WHEN account = 'REVENUE' THEN 1 END) AS revenue_count
          FROM ir_journal_entries GROUP BY document_type, document_id
        ), totals AS (
          SELECT NVL(SUM(d.amount), 0) AS document_net,
                 NVL(SUM(NVL(j.receivable, 0)), 0) AS receivable_net,
                 -NVL(SUM(NVL(j.revenue_debit, 0)), 0) AS revenue_net,
                 NVL(SUM(CASE WHEN NVL(j.receivable_count, 0) = 0 OR NVL(j.revenue_count, 0) = 0
                                    OR d.amount <> NVL(j.receivable, 0)
                                    OR d.amount <> -NVL(j.revenue_debit, 0) THEN 1 ELSE 0 END), 0) AS mismatched_documents
          FROM documents d LEFT JOIN journal j
            ON j.document_type = d.document_type AND j.document_id = d.document_id
        )
        SELECT totals.*,
               (SELECT COUNT(*) FROM ir_journal_entries orphan
                WHERE orphan.created_at >= #{from} AND orphan.created_at < #{to}
                  AND NOT EXISTS (SELECT 1 FROM ir_shipments s WHERE orphan.document_type = 'SHIPMENT'
                                  AND s.id = orphan.document_id AND s.status = 'POSTED')
                  AND NOT EXISTS (SELECT 1 FROM ir_returns r WHERE orphan.document_type = 'RETURN'
                                  AND r.id = orphan.document_id AND r.status = 'POSTED')) AS orphan_entries
        FROM totals
        """)
    Map<String, Object> financial(@Param("from") OffsetDateTime from, @Param("to") OffsetDateTime to);

    @Select("""
        SELECT l.id AS shipment_line_id, l.warehouse_id, l.sku, l.quantity, l.amount,
               l.returned_quantity, l.returned_amount,
               NVL(t.returned_quantity, 0) AS ledger_returned_quantity,
               NVL(t.returned_amount, 0) AS ledger_returned_amount
        FROM ir_shipments s JOIN ir_shipment_lines l ON l.shipment_id = s.id
        LEFT JOIN (
          SELECT rl.shipment_line_id, SUM(rl.quantity) AS returned_quantity, SUM(rl.amount) AS returned_amount
          FROM ir_return_lines rl JOIN ir_returns r ON r.id = rl.return_id
          WHERE r.status = 'POSTED' GROUP BY rl.shipment_line_id
        ) t ON t.shipment_line_id = l.id
        WHERE s.id = #{shipmentId} AND s.status = 'POSTED'
        ORDER BY l.id
        """)
    List<Map<String, Object>> returns(@Param("shipmentId") String shipmentId);
}

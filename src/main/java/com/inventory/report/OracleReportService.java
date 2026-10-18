package com.inventory.report;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NoSuchElementException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

/** Report results are individually consistent statements, not a cross-request snapshot. */
@Service
@Profile("oracle")
@ConditionalOnProperty(name = "inventory.api.enabled", havingValue = "true")
public class OracleReportService {
    private final OracleReportMapper mapper;
    public record Stock(String warehouse, String sku, BigDecimal balance, BigDecimal ledgerQuantity, BigDecimal difference) {}
    public record Financial(OffsetDateTime fromInclusive, OffsetDateTime toExclusive, BigDecimal documentNet,
                            BigDecimal receivableNet, BigDecimal revenueNet, long mismatchedDocuments, long orphanEntries) {}
    public record ReturnedLine(String shipmentLineId, String warehouse, String sku, BigDecimal quantity,
                               BigDecimal amount, BigDecimal returnedQuantity, BigDecimal returnedAmount,
                               BigDecimal ledgerReturnedQuantity, BigDecimal ledgerReturnedAmount) {}

    public OracleReportService(OracleReportMapper mapper) { this.mapper = mapper; }

    public Stock stock(String warehouse, String sku) {
        requireText(warehouse, 80);
        requireText(sku, 80);
        Map<String, Object> row = mapper.stock(warehouse, sku);
        if (row == null) throw new NoSuchElementException("stock not found");
        return new Stock(text(row, "warehouse_id"), text(row, "sku"), decimal(row, "balance"),
                decimal(row, "ledger_quantity"), decimal(row, "difference"));
    }

    /** [from, to) uses posting time; returns reduce both net sales and receivables. */
    public Financial financial(OffsetDateTime from, OffsetDateTime to) {
        if (from == null || to == null || !from.toInstant().isBefore(to.toInstant())) {
            throw new IllegalArgumentException("invalid period");
        }
        Map<String, Object> row = mapper.financial(from, to);
        if (row == null) throw new IllegalStateException("financial query returned no aggregate");
        return new Financial(from, to, decimal(row, "document_net"), decimal(row, "receivable_net"),
                decimal(row, "revenue_net"), decimal(row, "mismatched_documents").longValueExact(),
                decimal(row, "orphan_entries").longValueExact());
    }

    public List<ReturnedLine> returns(String shipmentId) {
        requireText(shipmentId, 36);
        return mapper.returns(shipmentId).stream().map(row -> new ReturnedLine(
                text(row, "shipment_line_id"), text(row, "warehouse_id"), text(row, "sku"),
                decimal(row, "quantity"), decimal(row, "amount"), decimal(row, "returned_quantity"),
                decimal(row, "returned_amount"), decimal(row, "ledger_returned_quantity"),
                decimal(row, "ledger_returned_amount"))).toList();
    }

    private static void requireText(String value, int limit) {
        if (value == null || value.isBlank() || value.length() > limit) throw new IllegalArgumentException("invalid query value");
    }
    private static Object value(Map<String, Object> row, String key) {
        Object value = row.get(key);
        if (value == null) value = row.get(key.toUpperCase(Locale.ROOT));
        if (value == null) throw new IllegalStateException("missing report column " + key);
        return value;
    }
    private static String text(Map<String, Object> row, String key) { return value(row, key).toString(); }
    private static BigDecimal decimal(Map<String, Object> row, String key) { return new BigDecimal(value(row, key).toString()); }
}

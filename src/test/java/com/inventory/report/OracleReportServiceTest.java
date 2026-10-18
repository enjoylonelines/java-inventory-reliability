package com.inventory.report;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import org.apache.ibatis.annotations.Select;
import org.junit.jupiter.api.Test;

class OracleReportServiceTest {
    private final OracleReportMapper mapper = mock(OracleReportMapper.class);
    private final OracleReportService service = new OracleReportService(mapper);

    @Test
    void stockReportsBothBalanceAndLedgerWithOracleUppercaseAliases() {
        when(mapper.stock("W", "S")).thenReturn(Map.of("WAREHOUSE_ID", "W", "SKU", "S", "BALANCE", 8,
                "LEDGER_QUANTITY", 9, "DIFFERENCE", -1));
        when(mapper.stock("W", "missing")).thenReturn(null);
        OracleReportService.Stock result = service.stock("W", "S");
        assertEquals(new BigDecimal("8"), result.balance());
        assertEquals(new BigDecimal("9"), result.ledgerQuantity());
        assertEquals(new BigDecimal("-1"), result.difference());
        assertThrows(NoSuchElementException.class, () -> service.stock("W", "missing"));
        assertThrows(IllegalArgumentException.class, () -> service.stock(" ", "S"));
    }

    @Test
    void financialUsesAnExplicitHalfOpenInstantRangeAndKeepsDiscrepancies() {
        OffsetDateTime from = OffsetDateTime.parse("2026-10-01T00:00:00+09:00");
        OffsetDateTime to = OffsetDateTime.parse("2026-11-01T00:00:00+09:00");
        when(mapper.financial(from, to)).thenReturn(Map.of("DOCUMENT_NET", "75.00", "RECEIVABLE_NET", "74.00",
                "REVENUE_NET", "75.00", "MISMATCHED_DOCUMENTS", 1, "ORPHAN_ENTRIES", 2));
        OracleReportService.Financial result = service.financial(from, to);
        assertEquals(new BigDecimal("75.00"), result.documentNet());
        assertEquals(new BigDecimal("74.00"), result.receivableNet());
        assertEquals(1, result.mismatchedDocuments());
        assertEquals(2, result.orphanEntries());
        assertThrows(IllegalArgumentException.class, () -> service.financial(from, from));
        assertThrows(IllegalArgumentException.class, () -> service.financial(to, from));
        assertThrows(IllegalArgumentException.class, () -> service.financial(null, to));
        verify(mapper, times(1)).financial(from, to);
    }

    @Test
    void returnsExposeStoredAndIndependentlySummedPostedTotals() {
        when(mapper.returns("shipment-1")).thenReturn(List.of(Map.of(
                "SHIPMENT_LINE_ID", "line-1", "WAREHOUSE_ID", "W", "SKU", "S", "QUANTITY", 3,
                "AMOUNT", "10.00", "RETURNED_QUANTITY", 2, "RETURNED_AMOUNT", "6.67",
                "LEDGER_RETURNED_QUANTITY", 1, "LEDGER_RETURNED_AMOUNT", "3.33")));
        OracleReportService.ReturnedLine result = service.returns("shipment-1").getFirst();
        assertEquals(new BigDecimal("2"), result.returnedQuantity());
        assertEquals(new BigDecimal("1"), result.ledgerReturnedQuantity());
        assertEquals(new BigDecimal("6.67"), result.returnedAmount());
        assertEquals(new BigDecimal("3.33"), result.ledgerReturnedAmount());
        assertThrows(IllegalArgumentException.class, () -> service.returns(""));
    }

    @Test
    void sqlContractsUsePostedDocumentsAndCorrectSignsWithoutClaimingDatabaseExecution() throws Exception {
        String sql = String.join(" ", OracleReportMapper.class.getMethod("financial", OffsetDateTime.class, OffsetDateTime.class)
                .getAnnotation(Select.class).value());
        assertTrue(sql.contains("posted_at >= #{from}"));
        assertTrue(sql.contains("posted_at < #{to}"));
        assertTrue(sql.contains("-NVL(SUM(l.amount), 0)"));
        assertTrue(sql.contains("-NVL(j.revenue_debit, 0)"));
        assertTrue(sql.contains("orphan.created_at >= #{from}"));
        String returnsSql = String.join(" ", OracleReportMapper.class.getMethod("returns", String.class)
                .getAnnotation(Select.class).value());
        assertTrue(returnsSql.contains("r.status = 'POSTED'"));
        String stockSql = String.join(" ", OracleReportMapper.class.getMethod("stock", String.class, String.class)
                .getAnnotation(Select.class).value());
        assertTrue(stockSql.contains("SUM(m.quantity_delta)"));
    }
}

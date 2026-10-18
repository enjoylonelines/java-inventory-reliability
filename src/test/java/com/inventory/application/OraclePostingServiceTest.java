package com.inventory.application;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.inventory.oracle.OracleInventoryMapper;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

class OraclePostingServiceTest {
    private OracleInventoryMapper db;
    private PlatformTransactionManager manager;
    private OraclePostingService service;

    @BeforeEach void setUp() {
        db = mock(OracleInventoryMapper.class);
        manager = mock(PlatformTransactionManager.class);
        when(manager.getTransaction(any())).thenAnswer(ignored -> new SimpleTransactionStatus());
        service = new OraclePostingService(db, new TransactionTemplate(manager), new ObjectMapper());
        when(db.findRequest(anyString())).thenReturn(null);
        when(db.insertRequest(anyString(), anyString(), anyString(), anyString())).thenReturn(1);
        when(db.insertActionAudit(anyString(), anyString(), anyString(), anyString(), anyString())).thenReturn(1);
        when(db.confirmShipment(anyString())).thenReturn(1);
        when(db.postShipment(anyString())).thenReturn(1);
        when(db.confirmReturn(anyString())).thenReturn(1);
        when(db.postReturn(anyString())).thenReturn(1);
        when(db.lockStock(anyString(), anyString())).thenReturn(BigDecimal.TEN);
        when(db.decrementStock(anyString(), anyString(), any())).thenReturn(1);
        when(db.incrementStock(anyString(), anyString(), any())).thenReturn(1);
        when(db.reserveReturn(anyString(), any(), any())).thenReturn(1);
    }

    @Test void shipmentWritesBalancedEntriesAndSnapshotWithinTransaction() {
        var command = new OraclePostingService.ShipmentCommand(List.of(
                new OraclePostingService.ShipmentItem("warehouse", "sku", 3, new BigDecimal("2.50"))));
        var result = service.postShipment("one", command);
        assertTrue(result.created());
        verify(db).decrementStock("warehouse", "sku", new BigDecimal("3"));
        verify(db).insertJournal(anyString(), eq("SHIPMENT"), eq(result.documentId()), anyString(), eq("RECEIVABLE"), eq(new BigDecimal("7.50")));
        verify(db).insertJournal(anyString(), eq("SHIPMENT"), eq(result.documentId()), anyString(), eq("REVENUE"), eq(new BigDecimal("-7.50")));
        ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
        verify(db).insertOutbox(anyString(), eq("SHIPMENT"), eq(result.documentId()), payload.capture());
        verify(db).insertActionAudit(anyString(), eq("system"), eq("POST"), eq("SHIPMENT"), eq(result.documentId()));
        assertTrue(payload.getValue().contains("\"sku\":\"sku\""));
        assertTrue(payload.getValue().contains("\"quantityDelta\":-3"));
        verify(manager).commit(any());
    }

    @Test void insufficientStockRollsBackAndDoesNotPublish() {
        when(db.decrementStock(anyString(), anyString(), any())).thenReturn(0);
        var command = new OraclePostingService.ShipmentCommand(List.of(
                new OraclePostingService.ShipmentItem("w", "s", 2, BigDecimal.ONE)));
        assertThrows(OraclePostingService.StockUnavailable.class, () -> service.postShipment("two", command));
        verify(manager).rollback(any());
        verify(db, never()).insertOutbox(anyString(), anyString(), anyString(), anyString());
    }

    @Test void returnUsesLockedProgressAndWritesReverseAmount() {
        when(db.lockShipment("shipment")).thenReturn(Map.of("STATUS", "POSTED"));
        when(db.lockShipmentLine("line")).thenReturn(Map.of("SHIPMENT_ID", "shipment", "WAREHOUSE_ID", "w", "SKU", "s",
                "QUANTITY", new BigDecimal("3"), "AMOUNT", new BigDecimal("10.00"),
                "RETURNED_QUANTITY", BigDecimal.ONE, "RETURNED_AMOUNT", new BigDecimal("3.33")));
        var command = new OraclePostingService.ReturnCommand("shipment", List.of(new OraclePostingService.ReturnItem("line", 1)));
        var result = service.postReturn("return-key", command);
        verify(db).reserveReturn("line", BigDecimal.ONE, new BigDecimal("3.34"));
        verify(db).insertJournal(anyString(), eq("RETURN"), eq(result.documentId()), anyString(), eq("RECEIVABLE"), eq(new BigDecimal("-3.34")));
        ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
        verify(db).insertOutbox(anyString(), eq("RETURN"), eq(result.documentId()), payload.capture());
        assertTrue(payload.getValue().contains("\"sku\":\"s\""));
        assertTrue(payload.getValue().contains("\"receivableDelta\":-3.34"));
        verify(manager).commit(any());
    }

    @Test void sameKeyDifferentCommandIsRejectedBeforeWrites() throws Exception {
        var command = new OraclePostingService.ShipmentCommand(List.of(new OraclePostingService.ShipmentItem("w", "s", 1, BigDecimal.ONE)));
        when(db.findRequest("used")).thenReturn(Map.of("FINGERPRINT", "different", "DOCUMENT_TYPE", "SHIPMENT", "DOCUMENT_ID", "original"));
        assertThrows(OraclePostingService.RequestConflict.class, () -> service.postShipment("used", command));
        verify(db, never()).insertOrder(anyString());
        verify(manager).rollback(any());
    }
}

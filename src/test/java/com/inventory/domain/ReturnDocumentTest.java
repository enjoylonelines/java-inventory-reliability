package com.inventory.domain;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ReturnDocumentTest {
    private static Money money(String amount) { return new Money(new BigDecimal(amount)); }
    private static ShipmentLine source(String id, long quantity, String amount) {
        return new ShipmentLine(id, "wh", "sku-" + id, new Quantity(quantity), money(amount));
    }
    private static Shipment posted(ShipmentLine... lines) {
        return Shipment.draft("out-1", List.of(lines)).confirm().post();
    }
    private static ReturnDocument draft(Shipment shipment, long quantity) {
        return ReturnDocument.draft("return-1", shipment, List.of(new ReturnLine("line-1", new Quantity(quantity))));
    }

    @Test
    void onlyPostedSourceAndValidDistinctSourceLinesAreAccepted() {
        ShipmentLine source = source("line-1", 3, "10.00");
        Shipment unposted = Shipment.draft("s", List.of(source)).confirm();
        assertThrows(IllegalArgumentException.class, () -> draft(unposted, 1));
        Shipment shipment = posted(source);
        assertThrows(IllegalArgumentException.class, () -> ReturnDocument.draft("r", shipment, List.of()));
        assertThrows(IllegalArgumentException.class, () -> ReturnDocument.draft("r", shipment,
                List.of(new ReturnLine("other", new Quantity(1)))));
        assertThrows(IllegalArgumentException.class, () -> ReturnDocument.draft("r", shipment,
                List.of(new ReturnLine("line-1", new Quantity(1)), new ReturnLine("line-1", new Quantity(1)))));
    }

    @Test
    void returnStateTransitionsAreExplicitAndPreserveTheOriginalShipment() {
        Shipment shipment = posted(source("line-1", 3, "10.00"));
        ReturnDocument draft = draft(shipment, 1);
        assertThrows(IllegalStateException.class, () -> draft.post(Map.of("line-1", ReturnProgress.none())));
        ReturnDocument confirmed = draft.confirm();
        assertEquals(ReturnDocument.Status.DRAFT, draft.status());
        assertThrows(IllegalStateException.class, confirmed::confirm);
        ReturnDocument complete = confirmed.post(Map.of("line-1", ReturnProgress.none()));
        assertEquals(ReturnDocument.Status.POSTED, complete.status());
        assertEquals(Shipment.Status.POSTED, shipment.status());
        assertThrows(IllegalStateException.class, () -> complete.post(Map.of("line-1", ReturnProgress.none())));
        assertThrows(UnsupportedOperationException.class, () -> complete.allocations().clear());
    }

    @Test
    void threePartialReturnsExactlyReverseOriginalAmount() {
        Shipment shipment = posted(source("line-1", 3, "10.00"));
        ReturnProgress progress = ReturnProgress.none();
        String[] expected = {"3.33", "3.34", "3.33"};
        for (String amount : expected) {
            ReturnDocument returned = draft(shipment, 1).confirm().post(Map.of("line-1", progress));
            ReturnDocument.Allocation allocation = returned.allocations().getFirst();
            assertEquals(money(amount), allocation.amount());
            progress = allocation.after();
        }
        assertEquals(new ReturnProgress(3, money("10.00")), progress);
        ReturnProgress finalProgress = progress;
        assertThrows(IllegalArgumentException.class, () -> draft(shipment, 1).confirm().post(Map.of("line-1", finalProgress)));
    }

    @Test
    void rejectsMissingOrInconsistentProgressAndExcessReturns() {
        Shipment shipment = posted(source("line-1", 3, "10.00"));
        ReturnDocument document = draft(shipment, 2).confirm();
        assertThrows(NullPointerException.class, () -> document.post(Map.of()));
        assertThrows(IllegalArgumentException.class, () -> document.post(Map.of("line-1", new ReturnProgress(2, money("6.67")))));
        assertThrows(IllegalArgumentException.class, () -> document.post(Map.of("line-1", new ReturnProgress(1, money("3.34")))));
        assertThrows(IllegalArgumentException.class, () -> new ReturnProgress(-1, Money.zero()));
        assertEquals(ReturnDocument.Status.CONFIRMED, document.status());
        assertTrue(document.allocations().isEmpty());
    }

    @Test
    void everyLineMustPassBeforeAnyResultCanBePosted() {
        Shipment shipment = posted(source("line-1", 3, "10.00"), source("line-2", 2, "5.00"));
        ReturnDocument document = ReturnDocument.draft("r", shipment, List.of(
                new ReturnLine("line-1", new Quantity(1)), new ReturnLine("line-2", new Quantity(2)))).confirm();
        assertThrows(IllegalArgumentException.class, () -> document.post(Map.of(
                "line-1", ReturnProgress.none(), "line-2", new ReturnProgress(1, money("2.50")))));
        assertTrue(document.allocations().isEmpty());
        ReturnDocument posted = document.post(Map.of("line-1", ReturnProgress.none(), "line-2", ReturnProgress.none()));
        assertEquals(2, posted.allocations().size());
        assertEquals(money("5.00"), posted.allocations().get(1).amount());
    }

    @Test
    void handlesZeroAmountsAndLongBoundaryWithoutOverflow() {
        Shipment shipment = posted(source("line-1", Long.MAX_VALUE, "0.00"));
        ReturnDocument result = draft(shipment, 1).confirm().post(Map.of("line-1", new ReturnProgress(Long.MAX_VALUE - 1, Money.zero())));
        assertEquals(Long.MAX_VALUE, result.allocations().getFirst().after().returnedUnits());
        assertEquals(Money.zero(), result.allocations().getFirst().amount());
    }
}

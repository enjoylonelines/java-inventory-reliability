package com.inventory.domain;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class ShipmentTest {
    private ShipmentLine line() {
        return new ShipmentLine("line-1", "warehouse-1", "sku-1", new Quantity(3), new Money(new BigDecimal("10.00")));
    }

    @Test
    void stateTransitionsAreExplicitAndOriginalIsImmutable() {
        Shipment draft = Shipment.draft("shipment-1", List.of(line()));
        assertThrows(IllegalStateException.class, draft::post);
        Shipment confirmed = draft.confirm();
        assertEquals(Shipment.Status.DRAFT, draft.status());
        assertThrows(IllegalStateException.class, confirmed::confirm);
        Shipment posted = confirmed.post();
        assertEquals(Shipment.Status.POSTED, posted.status());
        assertThrows(IllegalStateException.class, posted::confirm);
        assertThrows(IllegalStateException.class, posted::post);
    }

    @Test
    void requiresValidUniqueLinesAndDefensivelyCopiesThem() {
        assertThrows(IllegalArgumentException.class, () -> Shipment.draft("", List.of(line())));
        assertThrows(IllegalArgumentException.class, () -> Shipment.draft("s", List.of()));
        assertThrows(IllegalArgumentException.class, () -> Shipment.draft("s", List.of(line(), line())));
        assertThrows(IllegalArgumentException.class, () -> new ShipmentLine("l", " ", "s", new Quantity(1), Money.zero()));
        ArrayList<ShipmentLine> input = new ArrayList<>(List.of(line()));
        Shipment shipment = Shipment.draft("s", input);
        input.clear();
        assertEquals(1, shipment.lines().size());
        assertThrows(UnsupportedOperationException.class, () -> shipment.lines().clear());
    }

    @Test
    void validatesUnitsAndCurrencyScale() {
        assertThrows(IllegalArgumentException.class, () -> new Quantity(0));
        assertThrows(IllegalArgumentException.class, () -> new Quantity(-1));
        assertThrows(IllegalArgumentException.class, () -> new Money(new BigDecimal("-0.01")));
        assertThrows(IllegalArgumentException.class, () -> new Money(new BigDecimal("1.001")));
        assertEquals(new BigDecimal("1.00"), new Money(BigDecimal.ONE).value());
    }

    @Test
    void restoresPersistedStateWhileRevalidatingItsContents() {
        Shipment restored = Shipment.restore("s", List.of(line()), Shipment.Status.POSTED);
        assertEquals(Shipment.Status.POSTED, restored.status());
        assertThrows(IllegalArgumentException.class, () -> Shipment.restore("s", List.of(), Shipment.Status.POSTED));
        assertThrows(NullPointerException.class, () -> Shipment.restore("s", List.of(line()), null));
    }
}

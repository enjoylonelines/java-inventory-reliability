package com.inventory.domain;

import java.util.Objects;

/** The source line determines SKU, warehouse and original amount. */
public record ReturnLine(String shipmentLineId, Quantity quantity) {
    public ReturnLine {
        ShipmentLine.requireText(shipmentLineId, "shipment line id");
        Objects.requireNonNull(quantity, "quantity");
    }
}

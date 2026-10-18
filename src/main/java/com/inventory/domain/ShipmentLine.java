package com.inventory.domain;

import java.util.Objects;

public record ShipmentLine(String id, String warehouseId, String sku, Quantity quantity, Money amount) {
    public ShipmentLine {
        requireText(id, "line id");
        requireText(warehouseId, "warehouse id");
        requireText(sku, "sku");
        Objects.requireNonNull(quantity, "quantity");
        Objects.requireNonNull(amount, "amount");
    }

    static void requireText(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " is required");
    }
}

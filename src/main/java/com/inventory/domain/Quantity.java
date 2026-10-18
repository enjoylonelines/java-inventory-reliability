package com.inventory.domain;

/** A positive quantity in indivisible units; fractional units are outside this model. */
public record Quantity(long units) {
    public Quantity {
        if (units <= 0) throw new IllegalArgumentException("quantity must be positive");
    }
}

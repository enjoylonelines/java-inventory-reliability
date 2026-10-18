package com.inventory.domain;

import java.util.Objects;

/** Previously posted return totals, read under the persistence layer's concurrency protection. */
public record ReturnProgress(long returnedUnits, Money reversedAmount) {
    public ReturnProgress {
        if (returnedUnits < 0) throw new IllegalArgumentException("returned units must not be negative");
        Objects.requireNonNull(reversedAmount, "reversedAmount");
    }

    public static ReturnProgress none() { return new ReturnProgress(0, Money.zero()); }
}

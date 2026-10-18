package com.inventory.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

/** Non-negative amounts in a single currency, with exactly two fractional digits. */
public record Money(BigDecimal value) {
    public Money {
        Objects.requireNonNull(value, "value");
        try {
            value = value.setScale(2, RoundingMode.UNNECESSARY);
        } catch (ArithmeticException e) {
            throw new IllegalArgumentException("amount must have at most two decimal places", e);
        }
        if (value.signum() < 0) throw new IllegalArgumentException("amount must not be negative");
    }

    public static Money zero() { return new Money(BigDecimal.ZERO); }

    /** Cumulative allocation makes the final return exactly equal to the original amount. */
    public Money allocated(long returnedUnits, long originalUnits) {
        if (originalUnits <= 0 || returnedUnits < 0 || returnedUnits > originalUnits) {
            throw new IllegalArgumentException("invalid allocation quantity");
        }
        return new Money(value.multiply(BigDecimal.valueOf(returnedUnits))
                .divide(BigDecimal.valueOf(originalUnits), 2, RoundingMode.HALF_UP));
    }

    public Money minus(Money other) {
        return new Money(value.subtract(Objects.requireNonNull(other, "other").value));
    }
}

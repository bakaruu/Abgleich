package dev.abgleich.domain.matching;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

/** How sure a rule is, from 0.00 to 1.00. A decimal, never a double (B01). */
public record Confidence(BigDecimal value) {

    public static final Confidence CERTAIN = new Confidence(BigDecimal.ONE);

    public Confidence {
        Objects.requireNonNull(value, "value");
        try {
            value = value.setScale(2, RoundingMode.UNNECESSARY);
        } catch (ArithmeticException e) {
            throw new IllegalArgumentException("Confidence has at most 2 decimals", e);
        }
        if (value.signum() < 0 || value.compareTo(BigDecimal.ONE) > 0) {
            throw new IllegalArgumentException("Confidence must be between 0.00 and 1.00");
        }
    }

    public static Confidence of(String value) {
        return new Confidence(new BigDecimal(value));
    }
}

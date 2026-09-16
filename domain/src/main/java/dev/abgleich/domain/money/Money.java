package dev.abgleich.domain.money;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Currency;
import java.util.List;
import java.util.Objects;

/**
 * An exact amount of money in a currency with two minor units (CHF, EUR).
 *
 * <p>The amount is always stored with scale 2, so {@code 1250.0} and {@code 1250.00}
 * are the same value (B02). Amounts with more than two decimals are rejected instead
 * of being silently rounded.
 */
public record Money(BigDecimal amount, Currency currency) implements Comparable<Money> {

    private static final int SCALE = 2;

    public static final Currency CHF = Currency.getInstance("CHF");
    public static final Currency EUR = Currency.getInstance("EUR");

    public Money {
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(currency, "currency");
        if (currency.getDefaultFractionDigits() != SCALE) {
            throw new IllegalArgumentException(
                    "Unsupported currency " + currency + ": only currencies with 2 minor units are supported");
        }
        try {
            amount = amount.setScale(SCALE, RoundingMode.UNNECESSARY);
        } catch (ArithmeticException e) {
            throw new IllegalArgumentException(
                    "Amount " + amount.toPlainString() + " has more than 2 decimals", e);
        }
    }

    public static Money of(String amount, Currency currency) {
        return new Money(new BigDecimal(amount), currency);
    }

    public static Money chf(String amount) {
        return of(amount, CHF);
    }

    public static Money eur(String amount) {
        return of(amount, EUR);
    }

    public static Money zero(Currency currency) {
        return new Money(BigDecimal.ZERO, currency);
    }

    public Money add(Money other) {
        requireSameCurrency(other);
        return new Money(amount.add(other.amount), currency);
    }

    public Money subtract(Money other) {
        requireSameCurrency(other);
        return new Money(amount.subtract(other.amount), currency);
    }

    public boolean isZero() {
        return amount.signum() == 0;
    }

    public boolean isPositive() {
        return amount.signum() > 0;
    }

    public boolean isNegative() {
        return amount.signum() < 0;
    }

    public boolean hasSameCurrencyAs(Money other) {
        return currency.equals(other.currency);
    }

    /**
     * Splits this amount into {@code parts} amounts that differ by at most one minor unit
     * and always add up exactly to this amount (B03). Leftover cents go to the first parts.
     */
    public List<Money> allocate(int parts) {
        if (parts < 1) {
            throw new IllegalArgumentException("parts must be at least 1, was " + parts);
        }
        BigInteger cents = amount.unscaledValue();
        BigInteger[] quotientAndRemainder = cents.divideAndRemainder(BigInteger.valueOf(parts));
        BigInteger base = quotientAndRemainder[0];
        int leftover = quotientAndRemainder[1].intValue();
        BigInteger step = BigInteger.valueOf(Integer.signum(leftover));

        List<Money> result = new ArrayList<>(parts);
        for (int i = 0; i < parts; i++) {
            BigInteger share = i < Math.abs(leftover) ? base.add(step) : base;
            result.add(new Money(new BigDecimal(share, SCALE), currency));
        }
        return List.copyOf(result);
    }

    @Override
    public int compareTo(Money other) {
        requireSameCurrency(other);
        return amount.compareTo(other.amount);
    }

    private void requireSameCurrency(Money other) {
        Objects.requireNonNull(other, "other");
        if (!hasSameCurrencyAs(other)) {
            throw new CurrencyMismatchException(currency, other.currency);
        }
    }

    @Override
    public String toString() {
        return currency.getCurrencyCode() + " " + amount.toPlainString();
    }
}

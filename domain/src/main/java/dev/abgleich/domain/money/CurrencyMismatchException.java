package dev.abgleich.domain.money;

import java.util.Currency;

/** Raised when two amounts in different currencies are combined or compared (B06). */
public final class CurrencyMismatchException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public CurrencyMismatchException(Currency left, Currency right) {
        super("Cannot combine " + left.getCurrencyCode() + " with " + right.getCurrencyCode());
    }
}

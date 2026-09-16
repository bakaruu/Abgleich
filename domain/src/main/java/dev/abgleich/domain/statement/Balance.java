package dev.abgleich.domain.statement;

import dev.abgleich.domain.money.Money;
import java.time.LocalDate;
import java.util.Objects;

/** A signed account balance on a booking date. Negative means the account is overdrawn. */
public record Balance(Money amount, LocalDate date) {

    public Balance {
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(date, "date");
    }
}

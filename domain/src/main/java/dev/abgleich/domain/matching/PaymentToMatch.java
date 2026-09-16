package dev.abgleich.domain.matching;

import dev.abgleich.domain.account.Iban;
import dev.abgleich.domain.money.Direction;
import dev.abgleich.domain.money.Money;
import dev.abgleich.domain.reference.PaymentReference;
import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

/** A stored, still unmatched bank transaction, with the version it was read at (B22). */
public record PaymentToMatch(
        UUID transactionId,
        Iban account,
        Direction direction,
        Money amount,
        PaymentReference reference,
        LocalDate bookingDate,
        long version) {

    public PaymentToMatch {
        Objects.requireNonNull(transactionId, "transactionId");
        Objects.requireNonNull(account, "account");
        Objects.requireNonNull(direction, "direction");
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(bookingDate, "bookingDate");
        reference = Objects.requireNonNullElseGet(reference, PaymentReference::none);
    }
}

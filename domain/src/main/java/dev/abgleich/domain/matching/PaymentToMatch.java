package dev.abgleich.domain.matching;

import dev.abgleich.domain.account.Iban;
import dev.abgleich.domain.money.Direction;
import dev.abgleich.domain.money.Money;
import dev.abgleich.domain.reference.PaymentReference;
import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

/**
 * A stored, still undecided bank transaction, with the version it was read at (B22). Texts come from
 * the bank file and are untrusted.
 *
 * @param charges bank charges the file reports for this payment, or {@code null} (B07)
 * @param reversal whether the bank marked the entry as the reversal of an earlier one (B10)
 */
public record PaymentToMatch(
        UUID transactionId,
        Iban account,
        Direction direction,
        Money amount,
        PaymentReference reference,
        String remittanceText,
        String counterpartyName,
        String endToEndId,
        Money charges,
        boolean reversal,
        LocalDate bookingDate,
        long version) {

    public PaymentToMatch {
        Objects.requireNonNull(transactionId, "transactionId");
        Objects.requireNonNull(account, "account");
        Objects.requireNonNull(direction, "direction");
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(bookingDate, "bookingDate");
        reference = Objects.requireNonNullElseGet(reference, PaymentReference::none);
        if (charges != null && (!charges.hasSameCurrencyAs(amount) || charges.isNegative())) {
            throw new IllegalArgumentException("Charges must be zero or positive and in the payment currency");
        }
    }

    /** A credit with only amount, reference and date: enough for tests of the structured rules. */
    public static PaymentToMatch credit(UUID transactionId, Iban account, Money amount, PaymentReference reference,
            LocalDate bookingDate) {
        return new PaymentToMatch(transactionId, account, Direction.CREDIT, amount, reference, null, null, null, null,
                false, bookingDate, 0);
    }

    /** Everything a person could read as a hint: the remittance text and a free-text reference. */
    String searchableText() {
        String referenceText = reference instanceof PaymentReference.FreeText text ? text.value() : "";
        return (remittanceText == null ? "" : remittanceText) + " " + referenceText;
    }
}

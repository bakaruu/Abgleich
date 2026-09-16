package dev.abgleich.domain.statement;

import dev.abgleich.domain.money.Direction;
import dev.abgleich.domain.money.Money;
import dev.abgleich.domain.statement.InvalidStatementException.Reason;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

/**
 * A booked entry of a bank statement, as the bank reports it.
 *
 * <p>The amount is always positive; {@link Direction} says whether it entered or left the
 * account (B10). A batch entry groups several payments: its details must add up to the entry
 * amount, so a 3'000 entry with three payments of 1'000 is never counted as 9'000 (B09).
 * Dates are {@link LocalDate}, never instants, so no time zone can move a payment (B15).
 */
public record StatementEntry(
        Money amount,
        Direction direction,
        LocalDate bookingDate,
        LocalDate valueDate,
        String bankReference,
        boolean reversal,
        List<TransactionDetail> details) {

    public StatementEntry {
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(direction, "direction");
        Objects.requireNonNull(bookingDate, "bookingDate");
        Objects.requireNonNull(details, "details");
        if (!amount.isPositive()) {
            throw new InvalidStatementException(
                    Reason.INCONSISTENT_ENTRY, "Entry amount must be positive; the direction carries the sign");
        }
        valueDate = Objects.requireNonNullElse(valueDate, bookingDate);
        bankReference = Texts.blankToNull(bankReference);
        details = checkedDetails(amount, details);
    }

    private static List<TransactionDetail> checkedDetails(Money amount, List<TransactionDetail> details) {
        if (details.size() == 1 && details.getFirst().amount() == null) {
            return List.of(details.getFirst().withAmount(amount));
        }
        Money sum = Money.zero(amount.currency());
        for (TransactionDetail detail : details) {
            if (detail.amount() == null) {
                throw new InvalidStatementException(Reason.INCONSISTENT_ENTRY,
                        "Entry with " + details.size() + " transactions must state the amount of each one");
            }
            if (!detail.amount().hasSameCurrencyAs(amount)) {
                throw new InvalidStatementException(Reason.MIXED_CURRENCIES,
                        "Transaction in " + detail.amount().currency() + " inside an entry in " + amount.currency());
            }
            sum = sum.add(detail.amount());
        }
        if (!details.isEmpty() && !sum.equals(amount)) {
            throw new InvalidStatementException(Reason.INCONSISTENT_ENTRY,
                    "Transactions add up to " + sum + " but the entry amount is " + amount);
        }
        return List.copyOf(details);
    }
}

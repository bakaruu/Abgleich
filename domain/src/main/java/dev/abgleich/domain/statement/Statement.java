package dev.abgleich.domain.statement;

import dev.abgleich.domain.account.Iban;
import dev.abgleich.domain.money.Direction;
import dev.abgleich.domain.money.Money;
import dev.abgleich.domain.statement.InvalidStatementException.Reason;
import java.util.Currency;
import java.util.List;
import java.util.Objects;

/**
 * The movements of one account over one period, independent of the file format.
 *
 * <p>A statement only exists if its balances add up: opening balance plus credits minus debits
 * must equal the closing balance (B11). A truncated or misread file therefore never produces a
 * single movement.
 *
 * @param statementId the bank's identifier of the statement, or {@code null} if the format has none
 */
public record Statement(
        Iban account,
        String statementId,
        Balance openingBalance,
        Balance closingBalance,
        List<StatementEntry> entries) {

    public Statement {
        Objects.requireNonNull(account, "account");
        Objects.requireNonNull(openingBalance, "openingBalance");
        Objects.requireNonNull(closingBalance, "closingBalance");
        Objects.requireNonNull(entries, "entries");
        statementId = Texts.blankToNull(statementId);
        entries = List.copyOf(entries);
        requireBalanced(openingBalance.amount(), closingBalance.amount(), entries);
    }

    public Currency currency() {
        return openingBalance.amount().currency();
    }

    private static void requireBalanced(Money opening, Money closing, List<StatementEntry> entries) {
        requireSameCurrency(opening, closing);
        Money expected = opening;
        for (StatementEntry entry : entries) {
            requireSameCurrency(opening, entry.amount());
            expected = entry.direction() == Direction.CREDIT
                    ? expected.add(entry.amount())
                    : expected.subtract(entry.amount());
        }
        if (!expected.equals(closing)) {
            throw new InvalidStatementException(Reason.UNBALANCED,
                    "Opening balance " + opening + " and " + entries.size() + " entries give " + expected
                            + ", but the closing balance is " + closing);
        }
    }

    private static void requireSameCurrency(Money reference, Money other) {
        if (!other.hasSameCurrencyAs(reference)) {
            throw new InvalidStatementException(Reason.MIXED_CURRENCIES,
                    "Statement in " + reference.currency() + " contains an amount in " + other.currency());
        }
    }
}

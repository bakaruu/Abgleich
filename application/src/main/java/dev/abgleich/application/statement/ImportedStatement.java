package dev.abgleich.application.statement;

import dev.abgleich.domain.account.Iban;
import dev.abgleich.domain.statement.Balance;
import java.util.Objects;
import java.util.UUID;

/**
 * What was stored for one account of an imported file.
 *
 * @param newTransactions transactions stored by this import
 * @param knownTransactions transactions skipped because an earlier, overlapping file already stored them
 */
public record ImportedStatement(
        UUID importId,
        Iban account,
        Balance openingBalance,
        Balance closingBalance,
        int newTransactions,
        int knownTransactions) {

    public ImportedStatement {
        Objects.requireNonNull(importId, "importId");
        Objects.requireNonNull(account, "account");
        Objects.requireNonNull(openingBalance, "openingBalance");
        Objects.requireNonNull(closingBalance, "closingBalance");
        if (newTransactions < 0 || knownTransactions < 0) {
            throw new IllegalArgumentException("Transaction counts cannot be negative");
        }
    }
}

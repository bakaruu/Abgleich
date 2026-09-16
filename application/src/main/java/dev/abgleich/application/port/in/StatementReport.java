package dev.abgleich.application.port.in;

import dev.abgleich.application.port.out.StatementFormat;
import dev.abgleich.domain.account.Iban;
import dev.abgleich.domain.matching.AllocationStatus;
import dev.abgleich.domain.matching.MatchRule;
import dev.abgleich.domain.money.Direction;
import dev.abgleich.domain.money.Money;
import dev.abgleich.domain.statement.Balance;
import dev.abgleich.domain.statement.TransactionStatus;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * One imported statement with the transactions it stored and what reconciliation did with them.
 * Names and remittance texts come from bank files: untrusted text that views must escape (B32).
 */
public record StatementReport(
        UUID importId,
        Iban account,
        StatementFormat format,
        ImportSource source,
        Balance openingBalance,
        Balance closingBalance,
        Instant receivedAt,
        List<Line> transactions) {

    public StatementReport {
        Objects.requireNonNull(importId, "importId");
        Objects.requireNonNull(account, "account");
        Objects.requireNonNull(format, "format");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(openingBalance, "openingBalance");
        Objects.requireNonNull(closingBalance, "closingBalance");
        Objects.requireNonNull(receivedAt, "receivedAt");
        transactions = List.copyOf(transactions);
    }

    /**
     * A transaction, once per active allocation. The allocation fields are {@code null} when the
     * transaction has none; a tie shows the same transaction once per proposed invoice (B28).
     */
    public record Line(
            UUID transactionId,
            LocalDate bookingDate,
            Direction direction,
            Money amount,
            String counterpartyName,
            String remittanceText,
            String reference,
            TransactionStatus status,
            String invoiceNumber,
            MatchRule rule,
            AllocationStatus allocationStatus,
            String explanation) {

        public Line {
            Objects.requireNonNull(transactionId, "transactionId");
            Objects.requireNonNull(bookingDate, "bookingDate");
            Objects.requireNonNull(direction, "direction");
            Objects.requireNonNull(amount, "amount");
            Objects.requireNonNull(status, "status");
        }
    }
}

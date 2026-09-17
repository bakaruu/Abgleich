package dev.abgleich.application.reconciliation;

import dev.abgleich.domain.account.Iban;
import dev.abgleich.domain.invoice.InvoiceStatus;
import dev.abgleich.domain.matching.Confidence;
import dev.abgleich.domain.matching.MatchRule;
import dev.abgleich.domain.money.Money;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * A payment waiting for a person's decision, with its proposals. Texts come from bank files and are untrusted
 * (B32).
 *
 * @param version the payment version a decision must send back (B34)
 */
public record ReviewItem(
        UUID transactionId,
        long version,
        Iban account,
        LocalDate bookingDate,
        Money amount,
        String counterpartyName,
        String remittanceText,
        String reference,
        List<Proposal> proposals) {

    public ReviewItem {
        Objects.requireNonNull(transactionId, "transactionId");
        Objects.requireNonNull(account, "account");
        Objects.requireNonNull(amount, "amount");
        proposals = List.copyOf(proposals);
    }

    public record Proposal(UUID groupId, MatchRule rule, Confidence confidence, String explanation, List<Share> shares) {
        public Proposal {
            Objects.requireNonNull(groupId, "groupId");
            shares = List.copyOf(shares);
        }

        public boolean severalInvoices() {
            return shares.size() > 1;
        }
    }

    public record Share(
            UUID invoiceId,
            String invoiceNumber,
            String debtorName,
            Money invoiceAmount,
            Money outstanding,
            Money allocated,
            Money chargesWrittenOff,
            InvoiceStatus invoiceStatus,
            LocalDate dueDate) {
    }
}

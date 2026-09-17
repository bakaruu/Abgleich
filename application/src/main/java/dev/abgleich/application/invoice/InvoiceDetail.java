package dev.abgleich.application.invoice;

import dev.abgleich.domain.matching.AllocationStatus;
import dev.abgleich.domain.matching.MatchRule;
import dev.abgleich.domain.money.Money;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** The invoice with every allocation ever made to it, including rejected and reversed ones. */
public record InvoiceDetail(InvoiceView invoice, List<AllocationView> allocations) {

    public InvoiceDetail {
        Objects.requireNonNull(invoice, "invoice");
        allocations = List.copyOf(allocations);
    }

    public record AllocationView(
            UUID allocationId,
            UUID transactionId,
            LocalDate bookingDate,
            Money transactionAmount,
            String counterpartyName,
            Money amount,
            Money chargesWrittenOff,
            MatchRule rule,
            AllocationStatus status,
            String explanation,
            String decidedBy,
            Instant decidedAt,
            String decisionNote) {
    }
}

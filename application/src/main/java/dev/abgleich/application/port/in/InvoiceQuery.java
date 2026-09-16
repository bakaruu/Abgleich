package dev.abgleich.application.port.in;

import dev.abgleich.domain.account.Iban;
import dev.abgleich.domain.invoice.InvoiceStatus;
import dev.abgleich.domain.matching.AllocationStatus;
import dev.abgleich.domain.matching.MatchRule;
import dev.abgleich.domain.money.Money;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Read side of the invoices screen and API. */
public interface InvoiceQuery {

    /** @param status only invoices in this status, or all when {@code null}; ordered by number */
    List<InvoiceView> list(InvoiceStatus status, int limit);

    Optional<InvoiceDetail> detail(UUID invoiceId);

    record InvoiceView(
            UUID id,
            String number,
            Iban creditorAccount,
            String debtorName,
            Money amount,
            Money paidAmount,
            InvoiceStatus status,
            LocalDate dueDate,
            String reference,
            long version) {

        public Money outstanding() {
            return amount.subtract(paidAmount);
        }
    }

    /** The invoice with every allocation ever made to it, including rejected and reversed ones. */
    record InvoiceDetail(InvoiceView invoice, List<AllocationView> allocations) {
        public InvoiceDetail {
            Objects.requireNonNull(invoice, "invoice");
            allocations = List.copyOf(allocations);
        }
    }

    record AllocationView(
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

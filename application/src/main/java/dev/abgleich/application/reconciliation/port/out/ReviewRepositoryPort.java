package dev.abgleich.application.reconciliation.port.out;

import dev.abgleich.domain.invoice.Invoice;
import dev.abgleich.domain.invoice.InvoiceEvent;
import dev.abgleich.domain.matching.Allocation;
import dev.abgleich.domain.money.Money;
import dev.abgleich.domain.statement.TransactionStatus;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public interface ReviewRepositoryPort {

    Optional<ProposalGroup> findGroup(UUID groupId);

    /**
     * In one transaction: confirms the group, rejects the payment's other active proposals, updates the
     * invoices, marks the payment matched and stores the events these changes cause (B23).
     *
     * @throws StaleDataException if the payment, an allocation or an invoice changed since it was read (B33, B34)
     */
    void recordConfirmation(ProposalGroup group, List<Allocation> confirmed, List<Invoice> settledInvoices,
            List<Allocation> superseded, List<InvoiceEvent> events);

    /**
     * In one transaction: rejects the group; the payment stays proposed while other proposals remain,
     * otherwise it is unmatched again.
     *
     * @throws StaleDataException if the payment or an allocation changed since it was read (B33, B34)
     */
    void recordRejection(ProposalGroup group, List<Allocation> rejected);

    /**
     * @param allocations the allocations of the group
     * @param invoices the invoices of the group, at the version read
     * @param otherProposals the payment's other active proposals
     */
    record ProposalGroup(
            UUID groupId,
            UUID transactionId,
            long transactionVersion,
            TransactionStatus transactionStatus,
            Money transactionAmount,
            List<Allocation> allocations,
            List<Invoice> invoices,
            List<Allocation> otherProposals) {

        public ProposalGroup {
            Objects.requireNonNull(groupId, "groupId");
            Objects.requireNonNull(transactionId, "transactionId");
            Objects.requireNonNull(transactionStatus, "transactionStatus");
            Objects.requireNonNull(transactionAmount, "transactionAmount");
            allocations = List.copyOf(allocations);
            invoices = List.copyOf(invoices);
            otherProposals = List.copyOf(otherProposals);
        }
    }
}

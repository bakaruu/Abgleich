package dev.abgleich.application.reconciliation.service;

import static org.assertj.core.api.Assertions.assertThat;

import dev.abgleich.application.DecisionResult;
import dev.abgleich.application.DecisionResult.Outcome;
import dev.abgleich.application.StaleDataException;
import dev.abgleich.application.reconciliation.port.out.ReviewRepositoryPort;
import dev.abgleich.domain.account.Iban;
import dev.abgleich.domain.invoice.Invoice;
import dev.abgleich.domain.invoice.InvoiceEvent;
import dev.abgleich.domain.invoice.InvoiceNumber;
import dev.abgleich.domain.invoice.InvoiceStatus;
import dev.abgleich.domain.matching.Allocation;
import dev.abgleich.domain.matching.AllocationStatus;
import dev.abgleich.domain.matching.MatchRule;
import dev.abgleich.domain.money.Money;
import dev.abgleich.domain.reference.PaymentReference;
import dev.abgleich.domain.statement.TransactionStatus;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ReviewServiceTest {

    private static final Iban ACCOUNT = Iban.of("CH9300762011623852957");
    private static final Instant NOW = Instant.parse("2026-09-16T09:00:00Z");
    private static final long VERSION = 3;

    private final FakeReviews reviews = new FakeReviews();
    private final ReviewService service = new ReviewService(reviews, Clock.fixed(NOW, ZoneOffset.UTC));
    private final UUID transactionId = UUID.randomUUID();
    private Invoice invoice;
    private Allocation proposal;
    private Allocation competing;

    @BeforeEach
    void setUp() {
        invoice = Invoice.register(UUID.randomUUID(), InvoiceNumber.of("FV-2026-0087"), ACCOUNT, "Talleres Ruiz SL",
                Money.chf("1815.00"), PaymentReference.none(), LocalDate.of(2026, 9, 30));
        proposal = Allocation.propose(transactionId, invoice.id(), UUID.randomUUID(), Money.chf("1815.00"), null,
                MatchRule.R4, "FRA 87", NOW);
        competing = Allocation.propose(transactionId, UUID.randomUUID(), UUID.randomUUID(), Money.chf("1815.00"), null,
                MatchRule.R4, "FRA 87", NOW);
        reviews.group = group(List.of(proposal), List.of(invoice), List.of(competing));
    }

    @Test
    void confirming_settles_the_invoice_and_rejects_the_other_proposals() {
        DecisionResult result = service.confirm(proposal.groupId(), VERSION, "reviewer");

        assertThat(result).isEqualTo(DecisionResult.done("Confirmed: CHF 1815.00 allocated to FV-2026-0087."));
        assertThat(reviews.confirmed).singleElement().satisfies(a -> {
            assertThat(a.status()).isEqualTo(AllocationStatus.CONFIRMED);
            assertThat(a.decidedBy()).isEqualTo("reviewer");
            assertThat(a.decidedAt()).isEqualTo(NOW);
        });
        assertThat(reviews.settled).singleElement().extracting(Invoice::status).isEqualTo(InvoiceStatus.PAID);
        assertThat(reviews.events).as("B23: stored with the decision").singleElement()
                .satisfies(event -> assertThat(event.number()).isEqualTo(invoice.number()));
        assertThat(reviews.superseded).singleElement().satisfies(a -> {
            assertThat(a.status()).isEqualTo(AllocationStatus.REJECTED);
            assertThat(a.decisionNote()).isEqualTo(Allocation.SUPERSEDED);
        });
    }

    @Test
    void B33_double_confirm_is_idempotent() {
        reviews.group = group(List.of(proposal.confirm("reviewer", NOW)), List.of(invoice), List.of());

        DecisionResult second = service.confirm(proposal.groupId(), VERSION + 1, "reviewer");

        assertThat(second.outcome()).isEqualTo(Outcome.ALREADY_DONE);
        assertThat(reviews.writes).isZero();
    }

    @Test
    void B33_racing_double_confirm_reports_already_done_after_the_database_refuses_the_second() {
        reviews.raceWith = group(List.of(proposal.confirm("reviewer", NOW)), List.of(invoice), List.of());

        DecisionResult second = service.confirm(proposal.groupId(), VERSION, "reviewer");

        assertThat(second.outcome()).isEqualTo(Outcome.ALREADY_DONE);
    }

    @Test
    void B34_stale_decision_is_refused() {
        DecisionResult result = service.confirm(proposal.groupId(), VERSION - 1, "second reviewer");

        assertThat(result).isEqualTo(DecisionResult.stale());
        assertThat(result.message()).isEqualTo(
                "Someone else already decided on this payment. Reload to see its current state.");
        assertThat(reviews.writes).isZero();
    }

    @Test
    void B34_confirming_what_someone_else_rejected_is_refused() {
        reviews.group = group(List.of(proposal.reject("first reviewer", NOW, "Wrong customer")), List.of(invoice), List.of());

        assertThat(service.confirm(proposal.groupId(), VERSION + 1, "second reviewer")).isEqualTo(DecisionResult.stale());
    }

    @Test
    void B31_payment_to_a_cancelled_invoice_cannot_be_confirmed() {
        reviews.group = group(List.of(proposal), List.of(invoice.cancel()), List.of());

        DecisionResult result = service.confirm(proposal.groupId(), VERSION, "reviewer");

        assertThat(result.outcome()).isEqualTo(Outcome.REFUSED);
        assertThat(result.message()).contains("is cancelled");
        assertThat(reviews.writes).isZero();
    }

    @Test
    void rejecting_keeps_the_reason() {
        DecisionResult result = service.reject(proposal.groupId(), VERSION, "reviewer", "  Different customer ");

        assertThat(result.outcome()).isEqualTo(Outcome.DONE);
        assertThat(reviews.rejected).singleElement().extracting(Allocation::decisionNote).isEqualTo("Different customer");
    }

    @Test
    void unknown_proposal_is_not_found() {
        reviews.group = null;

        assertThat(service.reject(UUID.randomUUID(), 0, "reviewer", "x").outcome()).isEqualTo(Outcome.NOT_FOUND);
    }

    private ReviewRepositoryPort.ProposalGroup group(List<Allocation> allocations, List<Invoice> invoices,
            List<Allocation> others) {
        return new ReviewRepositoryPort.ProposalGroup(allocations.getFirst().groupId(), transactionId, VERSION,
                TransactionStatus.PROPOSED, Money.chf("1815.00"), allocations, invoices, others);
    }

    private static final class FakeReviews implements ReviewRepositoryPort {
        private ProposalGroup group;
        private ProposalGroup raceWith;
        private int writes;
        private final List<Allocation> confirmed = new ArrayList<>();
        private final List<InvoiceEvent> events = new ArrayList<>();
        private final List<Invoice> settled = new ArrayList<>();
        private final List<Allocation> superseded = new ArrayList<>();
        private final List<Allocation> rejected = new ArrayList<>();

        @Override
        public Optional<ProposalGroup> findGroup(UUID groupId) {
            return Optional.ofNullable(group);
        }

        @Override
        public void recordConfirmation(ProposalGroup read, List<Allocation> allocations, List<Invoice> invoices,
                List<Allocation> others, List<InvoiceEvent> invoiceEvents) {
            if (raceWith != null) {
                group = raceWith;
                throw new StaleDataException("the other click won");
            }
            writes++;
            confirmed.addAll(allocations);
            events.addAll(invoiceEvents);
            settled.addAll(invoices);
            superseded.addAll(others);
        }

        @Override
        public void recordRejection(ProposalGroup read, List<Allocation> allocations) {
            writes++;
            rejected.addAll(allocations);
        }
    }
}

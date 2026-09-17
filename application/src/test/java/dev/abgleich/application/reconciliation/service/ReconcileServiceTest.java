package dev.abgleich.application.reconciliation.service;

import static org.assertj.core.api.Assertions.assertThat;

import dev.abgleich.application.StaleDataException;
import dev.abgleich.application.invoice.port.out.InvoiceRepositoryPort;
import dev.abgleich.application.reconciliation.port.in.ReconciliationRun;
import dev.abgleich.application.reconciliation.port.out.ReconciliationRepositoryPort;
import dev.abgleich.domain.account.Iban;
import dev.abgleich.domain.invoice.Invoice;
import dev.abgleich.domain.invoice.InvoiceEvent;
import dev.abgleich.domain.invoice.InvoiceNumber;
import dev.abgleich.domain.invoice.InvoiceStatus;
import dev.abgleich.domain.matching.Allocation;
import dev.abgleich.domain.matching.AllocationStatus;
import dev.abgleich.domain.matching.Matcher;
import dev.abgleich.domain.matching.PaymentToMatch;
import dev.abgleich.domain.money.Direction;
import dev.abgleich.domain.money.Money;
import dev.abgleich.domain.reference.CreditorReference;
import dev.abgleich.domain.reference.PaymentReference;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Currency;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ReconcileServiceTest {

    private static final Iban ACCOUNT = Iban.of("CH9300762011623852957");
    private static final PaymentReference SCOR = new PaymentReference.Scor(CreditorReference.of("RF18539007547034"));
    private static final Instant NOW = Instant.parse("2026-09-16T06:00:00Z");

    private final FakeInvoices invoices = new FakeInvoices();
    private final FakeReconciliations reconciliations = new FakeReconciliations();
    private final ReconcileService service = new ReconcileService(reconciliations, invoices, new Matcher(),
            Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void confirms_r1_proposes_the_rest_and_counts_every_outcome() {
        Invoice exact = invoice("F-2026-0142", "480.00", SCOR);
        invoices.add(exact);
        invoices.add(invoice("FV-2026-0087", "1815.00", PaymentReference.none()));
        reconciliations.pending.add(credit("480.00", SCOR, null));
        reconciliations.pending.add(credit("1815.00", PaymentReference.none(), "FRA 87"));
        reconciliations.pending.add(credit("99.00", PaymentReference.none(), "Spende"));

        ReconciliationRun run = service.reconcilePending(ACCOUNT);

        assertThat(run).isEqualTo(new ReconciliationRun(3, 1, 1, 1, 0, 0, 0));
        assertThat(reconciliations.confirmed).singleElement().extracting(Allocation::status).isEqualTo(AllocationStatus.CONFIRMED);
        assertThat(invoices.byId.get(exact.id()).status()).isEqualTo(InvoiceStatus.PAID);
        assertThat(reconciliations.proposed).singleElement().extracting(Allocation::rule)
                .isEqualTo(dev.abgleich.domain.matching.MatchRule.R4);
    }

    @Test
    void B23_auto_confirmation_hands_the_invoice_paid_event_to_the_same_write() {
        Invoice exact = invoice("F-2026-0142", "480.00", SCOR);
        invoices.add(exact);
        reconciliations.pending.add(credit("480.00", SCOR, null));

        service.reconcilePending(ACCOUNT);

        assertThat(reconciliations.events).singleElement().satisfies(event -> {
            assertThat(event).isInstanceOf(InvoiceEvent.InvoicePaid.class);
            assertThat(event.invoiceId()).isEqualTo(exact.id());
            assertThat(event.occurredAt()).isEqualTo(NOW);
        });
    }

    @Test
    void B23_a_refused_write_leaves_no_event_behind() {
        invoices.add(invoice("F-2026-0142", "480.00", SCOR));
        reconciliations.pending.add(credit("480.00", SCOR, null));
        reconciliations.staleWrites = Integer.MAX_VALUE;

        service.reconcilePending(ACCOUNT);

        assertThat(reconciliations.events).isEmpty();
    }

    @Test
    void rejected_proposals_are_not_proposed_again() {
        Invoice invoice = invoice("FV-2026-0087", "1815.00", PaymentReference.none());
        invoices.add(invoice);
        PaymentToMatch payment = credit("1815.00", PaymentReference.none(), "FRA 87");
        reconciliations.pending.add(payment);
        reconciliations.rejected.put(payment.transactionId(), Set.of(Set.of(invoice.id())));

        ReconciliationRun run = service.reconcilePending(ACCOUNT);

        assertThat(run.unmatched()).isEqualTo(1);
        assertThat(reconciliations.proposed).isEmpty();
    }

    @Test
    void B22_payment_decided_by_a_concurrent_run_is_not_decided_twice() {
        invoices.add(invoice("F-2026-0142", "480.00", SCOR));
        reconciliations.pending.add(credit("480.00", SCOR, null));
        reconciliations.staleWrites = 1;
        reconciliations.decidedElsewhere = true;

        ReconciliationRun run = service.reconcilePending(ACCOUNT);

        assertThat(run.handledElsewhere()).isEqualTo(1);
        assertThat(reconciliations.confirmed).isEmpty();
    }

    @Test
    void B22_invoice_paid_by_a_concurrent_run_is_decided_again_with_fresh_data() {
        Invoice invoice = invoice("F-2026-0142", "480.00", SCOR);
        invoices.add(invoice);
        reconciliations.pending.add(credit("480.00", SCOR, null));
        reconciliations.staleWrites = 1;
        reconciliations.onStale = () -> invoices.byId.put(invoice.id(), invoice.withConfirmedPayment(Money.chf("480.00")));

        ReconciliationRun run = service.reconcilePending(ACCOUNT);

        assertThat(run.sentToReview()).as("now a possible duplicate payment of a paid invoice (B31)").isEqualTo(1);
        assertThat(reconciliations.confirmed).isEmpty();
        assertThat(reconciliations.proposed.getFirst().explanation()).contains("possible duplicate payment");
    }

    @Test
    void B22_payment_that_keeps_conflicting_is_left_for_the_next_run() {
        invoices.add(invoice("F-2026-0142", "480.00", SCOR));
        reconciliations.pending.add(credit("480.00", SCOR, null));
        reconciliations.staleWrites = Integer.MAX_VALUE;

        ReconciliationRun run = service.reconcilePending(ACCOUNT);

        assertThat(run.deferred()).isEqualTo(1);
        assertThat(reconciliations.writeAttempts).isEqualTo(ReconcileService.MAX_ATTEMPTS);
    }

    @Test
    void B10_bank_reversal_undoes_the_confirmed_payment_with_a_note() {
        Invoice paid = invoice("F-2026-0142", "480.00", SCOR).withConfirmedPayment(Money.chf("480.00"));
        Allocation confirmed = Allocation.propose(UUID.randomUUID(), paid.id(), UUID.randomUUID(), Money.chf("480.00"),
                null, dev.abgleich.domain.matching.MatchRule.R1, "exact", NOW).confirm(Allocation.SYSTEM, NOW);
        PaymentToMatch reversal = new PaymentToMatch(UUID.randomUUID(), ACCOUNT, Direction.DEBIT, Money.chf("480.00"),
                SCOR, null, null, null, null, true, LocalDate.of(2026, 9, 17), 0);
        reconciliations.reversals.add(reversal);
        reconciliations.original = new ReconciliationRepositoryPort.ReversedPayment(confirmed.transactionId(), 1,
                List.of(confirmed), List.of(paid));

        ReconciliationRun run = service.reconcilePending(ACCOUNT);

        assertThat(run.reversed()).isEqualTo(1);
        assertThat(reconciliations.reversedAllocations).singleElement().satisfies(allocation -> {
            assertThat(allocation.status()).isEqualTo(AllocationStatus.REVERSED);
            assertThat(allocation.decisionNote()).isEqualTo("Reversed by the bank on 2026-09-17 (CHF 480.00)");
        });
        assertThat(reconciliations.reopenedInvoices).singleElement()
                .extracting(Invoice::status).isEqualTo(InvoiceStatus.OPEN);
        assertThat(reconciliations.events).singleElement().isInstanceOf(InvoiceEvent.InvoiceReopened.class);
    }

    private static Invoice invoice(String number, String amount, PaymentReference reference) {
        return Invoice.register(UUID.randomUUID(), InvoiceNumber.of(number), ACCOUNT, "Keller GmbH",
                Money.chf(amount), reference, LocalDate.of(2026, 9, 30));
    }

    private static PaymentToMatch credit(String amount, PaymentReference reference, String text) {
        return new PaymentToMatch(UUID.randomUUID(), ACCOUNT, Direction.CREDIT, Money.chf(amount), reference, text,
                null, null, null, false, LocalDate.of(2026, 9, 15), 0);
    }

    private static final class FakeInvoices implements InvoiceRepositoryPort {
        private final Map<UUID, Invoice> byId = new HashMap<>();

        @Override
        public void add(Invoice invoice) {
            byId.put(invoice.id(), invoice);
        }

        @Override
        public Optional<Invoice> findById(UUID id) {
            return Optional.ofNullable(byId.get(id));
        }

        @Override
        public List<Invoice> findCandidates(Iban creditorAccount, Currency currency, PaymentReference reference) {
            return byId.values().stream()
                    .filter(i -> i.status().acceptsPayments() || i.reference().equals(reference))
                    .toList();
        }

        @Override
        public void update(Invoice invoice) {
            byId.put(invoice.id(), invoice);
        }
    }

    private final class FakeReconciliations implements ReconciliationRepositoryPort {
        private final List<PaymentToMatch> pending = new ArrayList<>();
        private final List<PaymentToMatch> reversals = new ArrayList<>();
        private final Map<UUID, Set<Set<UUID>>> rejected = new HashMap<>();
        private final List<Allocation> confirmed = new ArrayList<>();
        private final List<InvoiceEvent> events = new ArrayList<>();
        private final List<Allocation> proposed = new ArrayList<>();
        private final List<Allocation> reversedAllocations = new ArrayList<>();
        private final List<Invoice> reopenedInvoices = new ArrayList<>();
        private ReversedPayment original;
        private int staleWrites;
        private int writeAttempts;
        private boolean decidedElsewhere;
        private Runnable onStale = () -> { };

        @Override
        public List<PaymentToMatch> findPendingCredits(Iban account) {
            return List.copyOf(pending);
        }

        @Override
        public Optional<PaymentToMatch> findPendingCredit(UUID transactionId) {
            return decidedElsewhere
                    ? Optional.empty()
                    : pending.stream().filter(p -> p.transactionId().equals(transactionId)).findFirst();
        }

        @Override
        public Set<Set<UUID>> rejectedInvoiceSets(UUID transactionId) {
            return rejected.getOrDefault(transactionId, new HashSet<>());
        }

        @Override
        public void recordConfirmed(PaymentToMatch payment, List<Allocation> allocations, List<Invoice> settledInvoices,
                List<InvoiceEvent> invoiceEvents) {
            failIfStale();
            confirmed.addAll(allocations);
            events.addAll(invoiceEvents);
            settledInvoices.forEach(invoice -> invoices.byId.put(invoice.id(), invoice));
        }

        @Override
        public void recordProposals(PaymentToMatch payment, List<Allocation> proposals) {
            failIfStale();
            proposed.addAll(proposals);
        }

        @Override
        public List<PaymentToMatch> findPendingReversals(Iban account) {
            return List.copyOf(reversals);
        }

        @Override
        public Optional<ReversedPayment> findReversedCredit(PaymentToMatch reversal) {
            return Optional.ofNullable(original);
        }

        @Override
        public void recordReversal(PaymentToMatch reversal, ReversedPayment payment, List<Allocation> allocations,
                List<Invoice> reopened, List<InvoiceEvent> invoiceEvents) {
            reversedAllocations.addAll(allocations);
            reopenedInvoices.addAll(reopened);
            events.addAll(invoiceEvents);
        }

        private void failIfStale() {
            writeAttempts++;
            if (staleWrites > 0) {
                staleWrites--;
                onStale.run();
                throw new StaleDataException("version changed");
            }
        }
    }
}

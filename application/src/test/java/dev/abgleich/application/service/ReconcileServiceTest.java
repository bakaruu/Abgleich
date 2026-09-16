package dev.abgleich.application.service;

import static org.assertj.core.api.Assertions.assertThat;

import dev.abgleich.application.port.in.ReconciliationRun;
import dev.abgleich.application.port.out.InvoiceRepositoryPort;
import dev.abgleich.application.port.out.ReconciliationRepositoryPort;
import dev.abgleich.application.port.out.StaleDataException;
import dev.abgleich.domain.account.Iban;
import dev.abgleich.domain.invoice.Invoice;
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
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
    void confirms_r1_matches_and_counts_every_outcome() {
        Invoice invoice = invoice("F-2026-0142", "480.00", SCOR);
        invoices.add(invoice);
        reconciliations.unmatched.add(credit("480.00", SCOR));
        reconciliations.unmatched.add(credit("99.00", PaymentReference.none()));

        ReconciliationRun run = service.reconcilePending(ACCOUNT);

        assertThat(run).isEqualTo(new ReconciliationRun(2, 1, 0, 1, 0, 0));
        assertThat(reconciliations.confirmed).singleElement()
                .extracting(Allocation::status).isEqualTo(AllocationStatus.CONFIRMED);
        assertThat(invoices.byId.get(invoice.id()).status()).isEqualTo(InvoiceStatus.PAID);
    }

    @Test
    void B28_tie_is_stored_as_proposals() {
        invoices.add(invoice("F-2026-0199", "480.00", SCOR));
        invoices.add(invoice("F-2026-0200", "480.00", SCOR));
        reconciliations.unmatched.add(credit("480.00", SCOR));

        ReconciliationRun run = service.reconcilePending(ACCOUNT);

        assertThat(run.sentToReview()).isEqualTo(1);
        assertThat(reconciliations.proposed).hasSize(2);
        assertThat(reconciliations.confirmed).isEmpty();
    }

    @Test
    void B22_payment_decided_by_a_concurrent_run_is_not_decided_twice() {
        invoices.add(invoice("F-2026-0142", "480.00", SCOR));
        PaymentToMatch payment = credit("480.00", SCOR);
        reconciliations.unmatched.add(payment);
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
        reconciliations.unmatched.add(credit("480.00", SCOR));
        reconciliations.staleWrites = 1;
        // Meanwhile another payment paid the invoice.
        reconciliations.onStale = () -> invoices.byId.put(invoice.id(), invoice.withConfirmedPayment(Money.chf("480.00")));

        ReconciliationRun run = service.reconcilePending(ACCOUNT);

        assertThat(run.unmatched()).as("the invoice is no longer open").isEqualTo(1);
        assertThat(reconciliations.confirmed).isEmpty();
    }

    @Test
    void B22_payment_that_keeps_conflicting_is_left_for_the_next_run() {
        invoices.add(invoice("F-2026-0142", "480.00", SCOR));
        reconciliations.unmatched.add(credit("480.00", SCOR));
        reconciliations.staleWrites = Integer.MAX_VALUE;

        ReconciliationRun run = service.reconcilePending(ACCOUNT);

        assertThat(run.deferred()).isEqualTo(1);
        assertThat(reconciliations.writeAttempts).isEqualTo(ReconcileService.MAX_ATTEMPTS);
    }

    private static Invoice invoice(String number, String amount, PaymentReference reference) {
        return Invoice.register(UUID.randomUUID(), InvoiceNumber.of(number), ACCOUNT, "Keller GmbH",
                Money.chf(amount), reference, LocalDate.of(2026, 9, 30));
    }

    private static PaymentToMatch credit(String amount, PaymentReference reference) {
        return new PaymentToMatch(UUID.randomUUID(), ACCOUNT, Direction.CREDIT, Money.chf(amount), reference,
                LocalDate.of(2026, 9, 15), 0);
    }

    private static final class FakeInvoices implements InvoiceRepositoryPort {
        private final Map<UUID, Invoice> byId = new HashMap<>();

        @Override
        public void add(Invoice invoice) {
            byId.put(invoice.id(), invoice);
        }

        @Override
        public List<Invoice> findOpen(Iban creditorAccount, Currency currency) {
            return byId.values().stream().filter(i -> i.status().acceptsPayments()).toList();
        }
    }

    private final class FakeReconciliations implements ReconciliationRepositoryPort {
        private final List<PaymentToMatch> unmatched = new ArrayList<>();
        private final List<Allocation> confirmed = new ArrayList<>();
        private final List<Allocation> proposed = new ArrayList<>();
        private int staleWrites;
        private int writeAttempts;
        private boolean decidedElsewhere;
        private Runnable onStale = () -> { };

        @Override
        public List<PaymentToMatch> findUnmatchedCredits(Iban account) {
            return List.copyOf(unmatched);
        }

        @Override
        public Optional<PaymentToMatch> findUnmatchedCredit(UUID transactionId) {
            return decidedElsewhere
                    ? Optional.empty()
                    : unmatched.stream().filter(p -> p.transactionId().equals(transactionId)).findFirst();
        }

        @Override
        public void recordConfirmed(PaymentToMatch payment, Allocation allocation, Invoice paidInvoice) {
            failIfStale();
            confirmed.add(allocation);
            invoices.byId.put(paidInvoice.id(), paidInvoice);
        }

        @Override
        public void recordProposals(PaymentToMatch payment, List<Allocation> proposals) {
            failIfStale();
            proposed.addAll(proposals);
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

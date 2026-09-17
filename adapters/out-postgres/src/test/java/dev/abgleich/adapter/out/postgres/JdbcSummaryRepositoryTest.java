package dev.abgleich.adapter.out.postgres;

import static org.assertj.core.api.Assertions.assertThat;

import dev.abgleich.application.port.in.ImportSource;
import dev.abgleich.application.port.in.ReconciliationSummary;
import dev.abgleich.application.port.in.ReconciliationSummary.ChannelImports;
import dev.abgleich.application.port.in.ReconciliationSummary.Payments;
import dev.abgleich.application.port.in.ReconciliationSummary.RuleOutcome;
import dev.abgleich.application.port.out.NewStatementImport;
import dev.abgleich.application.port.out.ReviewRepositoryPort.ProposalGroup;
import dev.abgleich.application.port.out.StatementFormat;
import dev.abgleich.domain.account.Iban;
import dev.abgleich.domain.invoice.Invoice;
import dev.abgleich.domain.invoice.InvoiceEvent;
import dev.abgleich.domain.invoice.InvoiceNumber;
import dev.abgleich.domain.matching.Allocation;
import dev.abgleich.domain.matching.MatchRule;
import dev.abgleich.domain.matching.PaymentToMatch;
import dev.abgleich.domain.money.Direction;
import dev.abgleich.domain.money.Money;
import dev.abgleich.domain.reference.CreditorReference;
import dev.abgleich.domain.reference.PaymentReference;
import dev.abgleich.domain.statement.Balance;
import dev.abgleich.domain.statement.Statement;
import dev.abgleich.domain.statement.StatementEntry;
import dev.abgleich.domain.statement.TransactionDetail;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * One payment for each situation the Summary distinguishes: settled by R1, confirmed by a person after a
 * competing proposal was superseded, rejected by a person, still waiting, and a debit that is no payment.
 */
class JdbcSummaryRepositoryTest {

    private static final Iban ACCOUNT = Iban.of("CH9300762011623852957");
    private static final PaymentReference SCOR = new PaymentReference.Scor(CreditorReference.of("RF18539007547034"));
    private static final LocalDate SEP_15 = LocalDate.of(2026, 9, 15);
    private static final Instant NOW = Instant.parse("2026-09-16T06:00:00Z");

    private JdbcSummaryRepository summaries;
    private JdbcReconciliationRepository reconciliations;
    private JdbcReviewRepository reviews;
    private JdbcInvoiceRepository invoices;

    @BeforeEach
    void setUp() {
        TestDatabase.emptied();
        summaries = new JdbcSummaryRepository(TestDatabase.dataSource(), TestDatabase.transactions());
        reconciliations = new JdbcReconciliationRepository(TestDatabase.dataSource(), TestDatabase.transactions());
        reviews = new JdbcReviewRepository(TestDatabase.dataSource(), TestDatabase.transactions());
        invoices = new JdbcInvoiceRepository(TestDatabase.dataSource());
    }

    @Test
    void empty_database_gives_zero_counts_and_every_rule() {
        ReconciliationSummary summary = summaries.summary();

        assertThat(summary.payments()).isEqualTo(new Payments(0, 0, 0, 0, 0, 0));
        assertThat(summary.rules()).extracting(RuleOutcome::rule).containsExactly(MatchRule.values());
        assertThat(summary.unmatchedAmounts()).isEmpty();
        assertThat(summary.imports()).isEmpty();
    }

    @Test
    void counts_payments_amounts_rule_decisions_imports_and_pending_events() {
        Invoice scor = invoice("F-2026-0142", "480.00", SCOR);
        Invoice fra87 = invoice("FV-2026-0087", "1815.00", PaymentReference.none());
        Invoice fra88 = invoice("FV-2026-0088", "1815.00", PaymentReference.none());
        Invoice donation = invoice("F-2026-0200", "99.00", PaymentReference.none());
        Invoice partial = invoice("F-2026-0300", "300.00", PaymentReference.none());
        storeStatement(List.of(
                credit("480.00", "BNK-1", SCOR), credit("1815.00", "BNK-2", null), credit("99.00", "BNK-3", null),
                credit("250.00", "BNK-4", null),
                new StatementEntry(Money.chf("12.00"), Direction.DEBIT, SEP_15, null, "BNK-5", false, List.of())),
                "2632.00");

        // R1 without a person.
        PaymentToMatch scorPayment = payment("480.00");
        Allocation auto = Allocation.propose(scorPayment.transactionId(), scor.id(), UUID.randomUUID(),
                Money.chf("480.00"), null, MatchRule.R1, "exact", NOW).confirm(Allocation.SYSTEM, NOW);
        Invoice paid = scor.withConfirmedPayment(Money.chf("480.00"));
        reconciliations.recordConfirmed(scorPayment, List.of(auto), List.of(paid), events(scor, paid));

        // Two R4 proposals: a person confirms one, which supersedes the other.
        PaymentToMatch fraPayment = payment("1815.00");
        Allocation first = proposal(fraPayment, fra87, "1815.00", MatchRule.R4);
        Allocation second = proposal(fraPayment, fra88, "1815.00", MatchRule.R4);
        reconciliations.recordProposals(fraPayment, List.of(first, second));
        ProposalGroup group = reviews.findGroup(first.groupId()).orElseThrow();
        Invoice settled = group.invoices().getFirst().withConfirmedPayment(Money.chf("1815.00"));
        reviews.recordConfirmation(group, List.of(group.allocations().getFirst().confirm("reviewer", NOW)),
                List.of(settled), List.of(group.otherProposals().getFirst().reject("reviewer", NOW, Allocation.SUPERSEDED)),
                events(group.invoices().getFirst(), settled));

        // An R5 proposal a person rejects: the payment is unmatched again.
        PaymentToMatch donationPayment = payment("99.00");
        Allocation guess = proposal(donationPayment, donation, "99.00", MatchRule.R5);
        reconciliations.recordProposals(donationPayment, List.of(guess));
        ProposalGroup rejected = reviews.findGroup(guess.groupId()).orElseThrow();
        reviews.recordRejection(rejected, List.of(rejected.allocations().getFirst().reject("reviewer", NOW, "Donation")));

        // An R2 proposal nobody decided yet.
        PaymentToMatch partialPayment = payment("250.00");
        reconciliations.recordProposals(partialPayment, List.of(proposal(partialPayment, partial, "250.00", MatchRule.R2)));

        ReconciliationSummary summary = summaries.summary();

        assertThat(summary.payments()).as("the debit is not a payment").isEqualTo(new Payments(4, 1, 1, 1, 1, 0));
        assertThat(summary.unmatchedAmounts()).containsExactly(Money.chf("99.00"));
        assertThat(summary.waitingForReviewAmounts()).containsExactly(Money.chf("250.00"));
        assertThat(summary.rules()).contains(
                new RuleOutcome(MatchRule.R1, 1, 0, 0, 0),
                new RuleOutcome(MatchRule.R2, 0, 0, 0, 1),
                new RuleOutcome(MatchRule.R4, 0, 1, 0, 0),
                new RuleOutcome(MatchRule.R5, 0, 0, 1, 0));
        assertThat(summary.imports()).containsExactly(new ChannelImports(ImportSource.REST, 1));
        assertThat(summary.outboxPending()).isEqualTo(2);
    }

    private PaymentToMatch payment(String amount) {
        return reconciliations.findPendingCredits(ACCOUNT).stream()
                .filter(payment -> payment.amount().equals(Money.chf(amount))).findFirst().orElseThrow();
    }

    private Invoice invoice(String number, String amount, PaymentReference reference) {
        Invoice invoice = Invoice.register(UUID.randomUUID(), InvoiceNumber.of(number), ACCOUNT, "Keller GmbH",
                Money.chf(amount), reference, LocalDate.of(2026, 9, 30));
        invoices.add(invoice);
        return invoice;
    }

    private static Allocation proposal(PaymentToMatch payment, Invoice invoice, String amount, MatchRule rule) {
        return Allocation.propose(payment.transactionId(), invoice.id(), UUID.randomUUID(), Money.chf(amount), null, rule,
                "synthetic", NOW);
    }

    private static List<InvoiceEvent> events(Invoice before, Invoice after) {
        return InvoiceEvent.between(before, after, UUID::randomUUID, NOW).stream().toList();
    }

    private static StatementEntry credit(String amount, String bankReference, PaymentReference reference) {
        return new StatementEntry(Money.chf(amount), Direction.CREDIT, SEP_15, null, bankReference, false,
                List.of(new TransactionDetail(null, reference, null, "Keller GmbH", null, null)));
    }

    private static void storeStatement(List<StatementEntry> entries, String closing) {
        Statement statement = new Statement(ACCOUNT, "STMT-SUMMARY",
                new Balance(Money.chf("0.00"), SEP_15), new Balance(Money.chf(closing), SEP_15), entries);
        new JdbcStatementImportRepository(TestDatabase.dataSource(), TestDatabase.transactions())
                .store(List.of(new NewStatementImport(UUID.randomUUID(), ImportSource.REST, StatementFormat.CAMT053_V04,
                        "e".repeat(64), "MSG-SUMMARY", statement, NOW)));
    }
}

package dev.abgleich.adapter.out.postgres;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import dev.abgleich.application.port.in.ImportSource;
import dev.abgleich.application.port.out.NewStatementImport;
import dev.abgleich.application.port.out.ReconciliationRepositoryPort.ReversedPayment;
import dev.abgleich.application.port.out.StaleDataException;
import dev.abgleich.application.port.out.StatementFormat;
import dev.abgleich.domain.account.Iban;
import dev.abgleich.domain.invoice.Invoice;
import dev.abgleich.domain.invoice.InvoiceEvent;
import dev.abgleich.domain.invoice.InvoiceNumber;
import dev.abgleich.domain.matching.Allocation;
import dev.abgleich.domain.matching.AllocationStatus;
import dev.abgleich.domain.matching.MatchRule;
import dev.abgleich.domain.matching.Matcher;
import dev.abgleich.domain.matching.PaymentToMatch;
import dev.abgleich.domain.matching.ReconciliationDecision;
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
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class JdbcReconciliationRepositoryTest {

    private static final Iban ACCOUNT = Iban.of("CH9300762011623852957");
    private static final PaymentReference SCOR = new PaymentReference.Scor(CreditorReference.of("RF18539007547034"));
    private static final LocalDate SEP_14 = LocalDate.of(2026, 9, 14);
    private static final LocalDate SEP_15 = LocalDate.of(2026, 9, 15);
    private static final Instant NOW = Instant.parse("2026-09-16T06:00:00Z");

    private JdbcTemplate jdbc;
    private JdbcReconciliationRepository repository;
    private Invoice invoice;

    @BeforeEach
    void setUp() {
        jdbc = TestDatabase.emptied();
        repository = new JdbcReconciliationRepository(TestDatabase.dataSource(), TestDatabase.transactions());
        invoice = Invoice.register(UUID.randomUUID(), InvoiceNumber.of("F-2026-0142"), ACCOUNT, "Keller GmbH",
                Money.chf("480.00"), SCOR, LocalDate.of(2026, 9, 30));
        new JdbcInvoiceRepository(TestDatabase.dataSource()).add(invoice);
        storeStatement("a", List.of(
                entry("480.00", Direction.CREDIT, SEP_15, "BNK-1", false,
                        new TransactionDetail(null, SCOR, null, "Keller GmbH", "E2E-142", null, Money.chf("0.00"))),
                entry("12.00", Direction.DEBIT, SEP_14, "BNK-2", false),
                entry("99.00", Direction.CREDIT, SEP_14, "BNK-3", false,
                        new TransactionDetail(null, null, "Danke", null, null, null))),
                "567.00");
    }

    @Test
    void B10_only_unmatched_credits_are_candidates_oldest_first_with_every_hint() {
        List<PaymentToMatch> payments = repository.findPendingCredits(ACCOUNT);

        assertThat(payments).extracting(PaymentToMatch::amount).containsExactly(Money.chf("99.00"), Money.chf("480.00"));
        PaymentToMatch scor = payments.get(1);
        assertThat(scor.reference()).isEqualTo(SCOR);
        assertThat(scor.counterpartyName()).isEqualTo("Keller GmbH");
        assertThat(scor.endToEndId()).isEqualTo("E2E-142");
        assertThat(scor.charges()).isEqualTo(Money.chf("0.00"));
        assertThat(payments.getFirst().remittanceText()).isEqualTo("Danke");
    }

    @Test
    void confirmed_match_updates_payment_invoice_and_allocation_together() {
        PaymentToMatch payment = scorPayment();
        List<Allocation> confirmed = autoConfirmed(payment);

        Invoice settled = invoice.withConfirmedPayment(payment.amount());

        repository.recordConfirmed(payment, confirmed, List.of(settled), events(invoice, settled));

        assertThat(row("select status, version from bank_transaction where id = ?", payment.transactionId()))
                .containsEntry("status", "MATCHED").containsEntry("version", 1L);
        assertThat(row("select event_type, invoice_number, paid_amount, published_at from outbox_event where invoice_id = ?",
                invoice.id()))
                .as("B23: the event commits with the change")
                .containsEntry("event_type", "INVOICE_PAID").containsEntry("invoice_number", "F-2026-0142")
                .containsEntry("published_at", null);
        assertThat(row("select status, version from invoice where id = ?", invoice.id()))
                .containsEntry("status", "PAID").containsEntry("version", 1L);
        assertThat(row("select rule, status, decided_by, group_id from allocation where id = ?", confirmed.getFirst().id()))
                .containsEntry("rule", "R1").containsEntry("status", "CONFIRMED").containsEntry("decided_by", "system")
                .containsEntry("group_id", confirmed.getFirst().groupId());
        assertThat(repository.findPendingCredit(payment.transactionId())).isEmpty();
    }

    @Test
    void B22_stale_payment_version_writes_nothing() {
        PaymentToMatch payment = scorPayment();
        jdbc.update("update bank_transaction set version = version + 1 where id = ?", payment.transactionId());

        Throwable refused = catchThrowable(() -> repository.recordConfirmed(payment, autoConfirmed(payment),
                List.of(invoice.withConfirmedPayment(payment.amount())),
                events(invoice, invoice.withConfirmedPayment(payment.amount()))));

        assertThat(refused).isInstanceOf(StaleDataException.class);
        assertThat(row("select status, version from invoice where id = ?", invoice.id()))
                .containsEntry("status", "OPEN").containsEntry("version", 0L);
        assertThat(count("allocation")).isZero();
        assertThat(count("outbox_event")).as("B23: a rolled back change leaves no event").isZero();
    }

    @Test
    void B22_invoice_paid_meanwhile_rolls_back_the_payment_update() {
        PaymentToMatch payment = scorPayment();
        jdbc.update("update invoice set paid_amount = 480, status = 'PAID', version = 1 where id = ?", invoice.id());

        Throwable refused = catchThrowable(() -> repository.recordConfirmed(payment, autoConfirmed(payment),
                List.of(invoice.withConfirmedPayment(payment.amount())),
                events(invoice, invoice.withConfirmedPayment(payment.amount()))));

        assertThat(refused).isInstanceOf(StaleDataException.class).hasMessageContaining("F-2026-0142");
        assertThat(count("outbox_event")).as("B23").isZero();
        assertThat(row("select status, version from bank_transaction where id = ?", payment.transactionId()))
                .containsEntry("status", "UNMATCHED").containsEntry("version", 0L);
        assertThat(count("allocation")).isZero();
    }

    @Test
    void B33_second_active_allocation_for_the_same_pair_is_refused() {
        PaymentToMatch payment = scorPayment();
        jdbc.update("""
                insert into allocation (id, transaction_id, invoice_id, group_id, amount, rule, confidence, explanation,
                                        status, created_at)
                values (?, ?, ?, gen_random_uuid(), 480.00, 'R4', 0.80, 'Earlier proposal', 'PROPOSED', now())
                """, UUID.randomUUID(), payment.transactionId(), invoice.id());

        assertThat(catchThrowable(() -> repository.recordProposals(payment, List.of(proposal(payment, invoice.id())))))
                .isInstanceOf(StaleDataException.class);
        assertThat(row("select status from bank_transaction where id = ?", payment.transactionId()))
                .containsEntry("status", "UNMATCHED");
    }

    @Test
    void B07_proposals_keep_group_and_charges_written_off() {
        PaymentToMatch payment = scorPayment();
        UUID group = UUID.randomUUID();
        Allocation withCharges = Allocation.propose(payment.transactionId(), invoice.id(), group, Money.chf("472.50"),
                Money.chf("7.50"), MatchRule.R2, "Short by charges", NOW);

        repository.recordProposals(payment, List.of(withCharges));

        assertThat(row("select status from bank_transaction where id = ?", payment.transactionId()))
                .containsEntry("status", "PROPOSED");
        assertThat(row("select charges_written_off, decided_by, group_id from allocation where transaction_id = ?",
                payment.transactionId()))
                .containsEntry("decided_by", null).containsEntry("group_id", group)
                .extractingByKey("charges_written_off").isEqualTo(new java.math.BigDecimal("7.50"));
    }

    @Test
    void rejected_invoice_sets_are_grouped() {
        PaymentToMatch payment = scorPayment();
        UUID otherInvoice = UUID.randomUUID();
        jdbc.update("""
                insert into invoice (id, invoice_number, creditor_iban, debtor_name, amount, currency, due_date, status)
                values (?, 'F-2026-0143', ?, 'Keller GmbH', 100, 'CHF', date '2026-09-30', 'OPEN')
                """, otherInvoice, ACCOUNT.value());
        UUID group = UUID.randomUUID();
        for (UUID invoiceId : List.of(invoice.id(), otherInvoice)) {
            jdbc.update("""
                    insert into allocation (id, transaction_id, invoice_id, group_id, amount, rule, confidence, explanation,
                                            status, decided_by, decided_at, decision_note, created_at)
                    values (?, ?, ?, ?, 240.00, 'R6', 0.75, 'Two invoices', 'REJECTED', 'reviewer', now(), 'No', now())
                    """, UUID.randomUUID(), payment.transactionId(), invoiceId, group);
        }

        assertThat(repository.rejectedInvoiceSets(payment.transactionId()))
                .containsExactly(Set.of(invoice.id(), otherInvoice));
    }

    @Test
    void B10_reversal_finds_the_single_matched_credit_and_undoes_it_atomically() {
        PaymentToMatch payment = scorPayment();
        List<Allocation> confirmed = autoConfirmed(payment);
        repository.recordConfirmed(payment, confirmed, List.of(invoice.withConfirmedPayment(payment.amount())),
                events(invoice, invoice.withConfirmedPayment(payment.amount())));
        storeStatement("b", List.of(entry("480.00", Direction.DEBIT, LocalDate.of(2026, 9, 17), "BNK-9", true,
                new TransactionDetail(null, SCOR, null, null, "E2E-142", null))), "-480.00");

        PaymentToMatch reversal = repository.findPendingReversals(ACCOUNT).getFirst();
        ReversedPayment original = repository.findReversedCredit(reversal).orElseThrow();

        assertThat(original.transactionId()).isEqualTo(payment.transactionId());
        assertThat(original.allocations()).extracting(Allocation::id).containsExactly(confirmed.getFirst().id());
        Invoice paid = original.invoices().getFirst();
        repository.recordReversal(reversal, original,
                List.of(original.allocations().getFirst().reverse("system", NOW, "Reversed by the bank")),
                List.of(paid.withReversedPayment(Money.chf("480.00"))),
                events(paid, paid.withReversedPayment(Money.chf("480.00"))));

        assertThat(row("select status from bank_transaction where id = ?", payment.transactionId()))
                .containsEntry("status", "REVERSED");
        assertThat(row("select status, decision_note from allocation where id = ?", confirmed.getFirst().id()))
                .containsEntry("status", "REVERSED").containsEntry("decision_note", "Reversed by the bank");
        assertThat(row("select status, paid_amount from invoice where id = ?", invoice.id()))
                .containsEntry("status", "OPEN");
        assertThat(repository.findPendingReversals(ACCOUNT)).isEmpty();
        assertThat(jdbc.queryForList("select event_type from outbox_event order by position", String.class))
                .containsExactly("INVOICE_PAID", "INVOICE_REOPENED");
    }

    @Test
    void B10_reversal_without_a_unique_original_is_left_alone() {
        storeStatement("c", List.of(entry("480.00", Direction.DEBIT, SEP_15, "BNK-8", true,
                new TransactionDetail(null, SCOR, null, null, null, null))), "-480.00");

        PaymentToMatch reversal = repository.findPendingReversals(ACCOUNT).getFirst();

        assertThat(repository.findReversedCredit(reversal)).as("the SCOR credit is not matched yet").isEmpty();
    }

    private static List<InvoiceEvent> events(Invoice before, Invoice after) {
        return InvoiceEvent.between(before, after, UUID::randomUUID, NOW).stream().toList();
    }

    private PaymentToMatch scorPayment() {
        return repository.findPendingCredits(ACCOUNT).stream()
                .filter(p -> p.reference().equals(SCOR)).findFirst().orElseThrow();
    }

    private List<Allocation> autoConfirmed(PaymentToMatch payment) {
        return ((ReconciliationDecision.AutoConfirmed) new Matcher().decide(payment, List.of(invoice), NOW)).allocations();
    }

    private static Allocation proposal(PaymentToMatch payment, UUID invoiceId) {
        return Allocation.propose(payment.transactionId(), invoiceId, UUID.randomUUID(), payment.amount(), null,
                MatchRule.R1, "Creditor reference and amount match", NOW);
    }

    private Map<String, Object> row(String sql, UUID id) {
        return jdbc.queryForMap(sql, id);
    }

    private int count(String table) {
        return jdbc.queryForObject("select count(*) from " + table, Integer.class);
    }

    private static StatementEntry entry(String amount, Direction direction, LocalDate booked, String bankReference,
            boolean reversal, TransactionDetail... details) {
        return new StatementEntry(Money.chf(amount), direction, booked, null, bankReference, reversal, List.of(details));
    }

    private static void storeStatement(String seed, List<StatementEntry> entries, String closing) {
        Statement statement = new Statement(ACCOUNT, "STMT-" + seed,
                new Balance(Money.chf("0.00"), SEP_14), new Balance(Money.chf(closing), SEP_15), entries);
        new JdbcStatementImportRepository(TestDatabase.dataSource(), TestDatabase.transactions())
                .store(List.of(new NewStatementImport(UUID.randomUUID(), ImportSource.REST, StatementFormat.CAMT053_V04,
                        seed.repeat(64), "MSG-" + seed, statement, NOW)));
    }
}

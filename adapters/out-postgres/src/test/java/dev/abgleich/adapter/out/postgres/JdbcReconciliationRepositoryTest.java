package dev.abgleich.adapter.out.postgres;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import dev.abgleich.application.port.in.ImportSource;
import dev.abgleich.application.port.out.NewStatementImport;
import dev.abgleich.application.port.out.StaleDataException;
import dev.abgleich.application.port.out.StatementFormat;
import dev.abgleich.domain.account.Iban;
import dev.abgleich.domain.invoice.Invoice;
import dev.abgleich.domain.invoice.InvoiceNumber;
import dev.abgleich.domain.matching.Allocation;
import dev.abgleich.domain.matching.Confidence;
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
        storeStatement();
    }

    @Test
    void B10_only_unmatched_credits_are_candidates_oldest_first() {
        List<PaymentToMatch> payments = repository.findUnmatchedCredits(ACCOUNT);

        assertThat(payments).extracting(PaymentToMatch::amount)
                .containsExactly(Money.chf("99.00"), Money.chf("480.00"));
        assertThat(payments).extracting(PaymentToMatch::direction).containsOnly(Direction.CREDIT);
        assertThat(payments.get(1).reference()).isEqualTo(SCOR);
        assertThat(payments.get(1).version()).isZero();
    }

    @Test
    void confirmed_match_updates_payment_invoice_and_allocation_together() {
        PaymentToMatch payment = scorPayment();
        Allocation allocation = confirmed(payment);

        repository.recordConfirmed(payment, allocation, invoice.withConfirmedPayment(payment.amount()));

        assertThat(row("select status, version from bank_transaction where id = ?", payment.transactionId()))
                .containsEntry("status", "MATCHED").containsEntry("version", 1L);
        assertThat(row("select status, paid_amount, version from invoice where id = ?", invoice.id()))
                .containsEntry("status", "PAID").containsEntry("version", 1L);
        assertThat(row("select rule, confidence, status, decided_by from allocation where id = ?", allocation.id()))
                .containsEntry("rule", "R1").containsEntry("status", "CONFIRMED").containsEntry("decided_by", "system");
        assertThat(repository.findUnmatchedCredit(payment.transactionId())).isEmpty();
    }

    @Test
    void B22_stale_payment_version_writes_nothing() {
        PaymentToMatch payment = scorPayment();
        jdbc.update("update bank_transaction set version = version + 1 where id = ?", payment.transactionId());

        Throwable refused = catchThrowable(() ->
                repository.recordConfirmed(payment, confirmed(payment), invoice.withConfirmedPayment(payment.amount())));

        assertThat(refused).isInstanceOf(StaleDataException.class);
        assertThat(row("select status, version from invoice where id = ?", invoice.id()))
                .containsEntry("status", "OPEN").containsEntry("version", 0L);
        assertThat(count("allocation")).isZero();
    }

    @Test
    void B22_invoice_paid_meanwhile_rolls_back_the_payment_update() {
        PaymentToMatch payment = scorPayment();
        jdbc.update("update invoice set paid_amount = 480, status = 'PAID', version = 1 where id = ?", invoice.id());

        Throwable refused = catchThrowable(() ->
                repository.recordConfirmed(payment, confirmed(payment), invoice.withConfirmedPayment(payment.amount())));

        assertThat(refused).isInstanceOf(StaleDataException.class).hasMessageContaining("F-2026-0142");
        assertThat(row("select status, version from bank_transaction where id = ?", payment.transactionId()))
                .containsEntry("status", "UNMATCHED").containsEntry("version", 0L);
        assertThat(count("allocation")).isZero();
    }

    @Test
    void B33_second_active_allocation_for_the_same_pair_is_refused() {
        PaymentToMatch payment = scorPayment();
        Allocation proposal = proposal(payment);
        jdbc.update("""
                insert into allocation (id, transaction_id, invoice_id, amount, rule, confidence, explanation,
                                        status, created_at)
                values (?, ?, ?, 480.00, 'R4', 0.80, 'Earlier proposal', 'PROPOSED', now())
                """, UUID.randomUUID(), payment.transactionId(), invoice.id());

        assertThat(catchThrowable(() -> repository.recordProposals(payment, List.of(proposal))))
                .isInstanceOf(StaleDataException.class);
        assertThat(row("select status from bank_transaction where id = ?", payment.transactionId()))
                .containsEntry("status", "UNMATCHED");
    }

    @Test
    void proposals_mark_the_payment_for_review() {
        PaymentToMatch payment = scorPayment();

        repository.recordProposals(payment, List.of(proposal(payment)));

        assertThat(row("select status from bank_transaction where id = ?", payment.transactionId()))
                .containsEntry("status", "PROPOSED");
        assertThat(row("select status, decided_by from allocation where transaction_id = ?", payment.transactionId()))
                .containsEntry("status", "PROPOSED").containsEntry("decided_by", null);
    }

    private PaymentToMatch scorPayment() {
        return repository.findUnmatchedCredits(ACCOUNT).stream()
                .filter(p -> p.reference().equals(SCOR)).findFirst().orElseThrow();
    }

    private Allocation confirmed(PaymentToMatch payment) {
        ReconciliationDecision decision = new Matcher().decide(payment, List.of(invoice), NOW);
        return ((ReconciliationDecision.AutoConfirmed) decision).allocation();
    }

    private Allocation proposal(PaymentToMatch payment) {
        return Allocation.propose(payment.transactionId(), invoice.id(), payment.amount(), MatchRule.R1,
                Confidence.CERTAIN, "Creditor reference and amount match", NOW);
    }

    private Map<String, Object> row(String sql, UUID id) {
        return jdbc.queryForMap(sql, id);
    }

    private int count(String table) {
        return jdbc.queryForObject("select count(*) from " + table, Integer.class);
    }

    /** A SCOR payment booked on the 15th, an older credit without reference and a fee. */
    private static void storeStatement() {
        TransactionDetail scor = new TransactionDetail(null, SCOR, null, "Keller GmbH", null, null);
        TransactionDetail noReference = new TransactionDetail(null, null, "Danke", null, null, null);
        Statement statement = new Statement(ACCOUNT, "STMT-1",
                new Balance(Money.chf("0.00"), SEP_14), new Balance(Money.chf("567.00"), SEP_15),
                List.of(
                        new StatementEntry(Money.chf("480.00"), Direction.CREDIT, SEP_15, null, "BNK-1", false, List.of(scor)),
                        new StatementEntry(Money.chf("12.00"), Direction.DEBIT, SEP_14, null, "BNK-2", false, List.of()),
                        new StatementEntry(Money.chf("99.00"), Direction.CREDIT, SEP_14, null, "BNK-3", false, List.of(noReference))));
        new JdbcStatementImportRepository(TestDatabase.dataSource(), TestDatabase.transactions())
                .store(List.of(new NewStatementImport(UUID.randomUUID(), ImportSource.REST, StatementFormat.CAMT053_V04,
                        "a".repeat(64), "MSG-1", statement, NOW)));
    }
}

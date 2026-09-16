package dev.abgleich.adapter.out.postgres;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import dev.abgleich.application.port.in.ImportSource;
import dev.abgleich.application.port.in.InvoiceQuery.InvoiceDetail;
import dev.abgleich.application.port.in.ReviewQueueQuery.ReviewItem;
import dev.abgleich.application.port.out.NewStatementImport;
import dev.abgleich.application.port.out.ReviewRepositoryPort.ProposalGroup;
import dev.abgleich.application.port.out.StaleDataException;
import dev.abgleich.application.port.out.StatementFormat;
import dev.abgleich.domain.account.Iban;
import dev.abgleich.domain.invoice.Invoice;
import dev.abgleich.domain.invoice.InvoiceNumber;
import dev.abgleich.domain.invoice.InvoiceStatus;
import dev.abgleich.domain.matching.Allocation;
import dev.abgleich.domain.matching.AllocationStatus;
import dev.abgleich.domain.matching.MatchRule;
import dev.abgleich.domain.matching.PaymentToMatch;
import dev.abgleich.domain.money.Direction;
import dev.abgleich.domain.money.Money;
import dev.abgleich.domain.reference.PaymentReference;
import dev.abgleich.domain.statement.Balance;
import dev.abgleich.domain.statement.Statement;
import dev.abgleich.domain.statement.StatementEntry;
import dev.abgleich.domain.statement.TransactionDetail;
import dev.abgleich.domain.statement.TransactionStatus;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/** Review decisions and the read models of the review queue and invoices, against PostgreSQL. */
class JdbcReviewRepositoryTest {

    private static final Iban ACCOUNT = Iban.of("ES9121000418450200051332");
    private static final LocalDate SEP_15 = LocalDate.of(2026, 9, 15);
    private static final Instant NOW = Instant.parse("2026-09-16T06:00:00Z");

    private JdbcTemplate jdbc;
    private JdbcReviewRepository reviews;
    private JdbcReconciliationQueries queries;
    private PaymentToMatch payment;
    private Invoice first;
    private Invoice second;
    private Allocation proposalFirst;
    private Allocation proposalSecond;

    @BeforeEach
    void setUp() {
        jdbc = TestDatabase.emptied();
        reviews = new JdbcReviewRepository(TestDatabase.dataSource(), TestDatabase.transactions());
        queries = new JdbcReconciliationQueries(TestDatabase.dataSource());
        JdbcInvoiceRepository invoices = new JdbcInvoiceRepository(TestDatabase.dataSource());
        first = invoice("FV-2026-0087", "TALLERES RUIZ SL");
        second = invoice("FV-2026-0088", "TALLERES RUIZ SL");
        invoices.add(first);
        invoices.add(second);

        Statement statement = new Statement(ACCOUNT, null, new Balance(Money.eur("0.00"), SEP_15),
                new Balance(Money.eur("1815.00"), SEP_15), List.of(new StatementEntry(Money.eur("1815.00"),
                        Direction.CREDIT, SEP_15, null, null, false, List.of(new TransactionDetail(null,
                                PaymentReference.none(), "<b>TRANSF</b> FRA 87 O 88", "TALLERES RUIZ SL", null, null)))));
        new JdbcStatementImportRepository(TestDatabase.dataSource(), TestDatabase.transactions())
                .store(List.of(new NewStatementImport(UUID.randomUUID(), ImportSource.WEB, StatementFormat.NORMA43,
                        "d".repeat(64), null, statement, NOW)));
        JdbcReconciliationRepository reconciliations =
                new JdbcReconciliationRepository(TestDatabase.dataSource(), TestDatabase.transactions());
        payment = reconciliations.findPendingCredits(ACCOUNT).getFirst();
        proposalFirst = proposal(first);
        proposalSecond = proposal(second);
        reconciliations.recordProposals(payment, List.of(proposalFirst, proposalSecond));
    }

    @Test
    void review_queue_lists_pending_payments_with_each_proposal_and_its_invoices() {
        List<ReviewItem> queue = queries.pendingReview(10);

        assertThat(queries.pendingReviewCount()).isEqualTo(1);
        assertThat(queue).singleElement().satisfies(item -> {
            assertThat(item.transactionId()).isEqualTo(payment.transactionId());
            assertThat(item.version()).isEqualTo(1);
            assertThat(item.remittanceText()).as("stored as received, escaped only by views").isEqualTo("<b>TRANSF</b> FRA 87 O 88");
            assertThat(item.proposals()).hasSize(2).allSatisfy(proposal -> {
                assertThat(proposal.rule()).isEqualTo(MatchRule.R4);
                assertThat(proposal.shares()).singleElement().extracting(share -> share.outstanding())
                        .isEqualTo(Money.eur("1815.00"));
            });
            assertThat(item.proposals()).extracting(p -> p.shares().getFirst().invoiceNumber())
                    .containsExactly("FV-2026-0087", "FV-2026-0088");
        });
    }

    @Test
    void confirmation_settles_the_invoice_and_supersedes_the_other_proposal() {
        ProposalGroup group = reviews.findGroup(proposalFirst.groupId()).orElseThrow();
        assertThat(group.transactionStatus()).isEqualTo(TransactionStatus.PROPOSED);
        assertThat(group.otherProposals()).extracting(Allocation::id).containsExactly(proposalSecond.id());

        reviews.recordConfirmation(group,
                List.of(group.allocations().getFirst().confirm("reviewer", NOW)),
                List.of(group.invoices().getFirst().withConfirmedPayment(Money.eur("1815.00"))),
                List.of(group.otherProposals().getFirst().reject("reviewer", NOW, "Another proposal was confirmed")));

        assertThat(jdbc.queryForObject("select status from bank_transaction", String.class)).isEqualTo("MATCHED");
        assertThat(jdbc.queryForList("select status from allocation order by status", String.class))
                .containsExactly("CONFIRMED", "REJECTED");
        InvoiceDetail detail = queries.invoiceDetail(first.id()).orElseThrow();
        assertThat(detail.invoice().status()).isEqualTo(InvoiceStatus.PAID);
        assertThat(detail.allocations()).singleElement().satisfies(allocation -> {
            assertThat(allocation.status()).isEqualTo(AllocationStatus.CONFIRMED);
            assertThat(allocation.decidedBy()).isEqualTo("reviewer");
        });
        assertThat(queries.pendingReview(10)).isEmpty();
    }

    @Test
    void B33_second_confirmation_of_the_same_group_is_refused_by_the_database() {
        ProposalGroup group = reviews.findGroup(proposalFirst.groupId()).orElseThrow();
        List<Allocation> confirmed = List.of(group.allocations().getFirst().confirm("reviewer", NOW));
        List<Invoice> settled = List.of(group.invoices().getFirst().withConfirmedPayment(Money.eur("1815.00")));
        reviews.recordConfirmation(group, confirmed, settled, List.of());

        assertThat(catchThrowable(() -> reviews.recordConfirmation(group, confirmed, settled, List.of())))
                .isInstanceOf(StaleDataException.class);
        assertThat(jdbc.queryForObject("select paid_amount from invoice where id = ?", java.math.BigDecimal.class, first.id()))
                .isEqualByComparingTo("1815.00");
    }

    @Test
    void B34_decision_on_an_old_payment_version_is_refused_and_writes_nothing() {
        ProposalGroup group = reviews.findGroup(proposalFirst.groupId()).orElseThrow();
        jdbc.update("update bank_transaction set version = version + 1");

        assertThat(catchThrowable(() -> reviews.recordRejection(group,
                List.of(group.allocations().getFirst().reject("second reviewer", NOW, "No")))))
                .isInstanceOf(StaleDataException.class);
        assertThat(jdbc.queryForList("select status from allocation", String.class)).containsOnly("PROPOSED");
    }

    @Test
    void rejecting_the_last_proposal_makes_the_payment_unmatched_again() {
        ProposalGroup group = reviews.findGroup(proposalFirst.groupId()).orElseThrow();
        reviews.recordRejection(group, List.of(group.allocations().getFirst().reject("reviewer", NOW, "Not this one")));
        assertThat(jdbc.queryForObject("select status from bank_transaction", String.class)).isEqualTo("PROPOSED");

        ProposalGroup last = reviews.findGroup(proposalSecond.groupId()).orElseThrow();
        reviews.recordRejection(last, List.of(last.allocations().getFirst().reject("reviewer", NOW, "Neither")));

        assertThat(jdbc.queryForObject("select status from bank_transaction", String.class)).isEqualTo("UNMATCHED");
        assertThat(queries.invoices(InvoiceStatus.OPEN, 10)).hasSize(2);
        assertThat(queries.invoiceDetail(second.id()).orElseThrow().allocations()).singleElement()
                .satisfies(allocation -> assertThat(allocation.decisionNote()).isEqualTo("Neither"));
    }

    @Test
    void invoices_can_be_filtered_by_status() {
        assertThat(queries.invoices(null, 10)).extracting(invoice -> invoice.number())
                .containsExactly("FV-2026-0087", "FV-2026-0088");
        assertThat(queries.invoices(InvoiceStatus.PAID, 10)).isEmpty();
    }

    private Allocation proposal(Invoice invoice) {
        return Allocation.propose(payment.transactionId(), invoice.id(), UUID.randomUUID(), Money.eur("1815.00"), null,
                MatchRule.R4, "Invoice number found in the remittance text", NOW);
    }

    private static Invoice invoice(String number, String debtor) {
        return Invoice.register(UUID.randomUUID(), InvoiceNumber.of(number), ACCOUNT, debtor, Money.eur("1815.00"),
                PaymentReference.none(), LocalDate.of(2026, 9, 30));
    }
}

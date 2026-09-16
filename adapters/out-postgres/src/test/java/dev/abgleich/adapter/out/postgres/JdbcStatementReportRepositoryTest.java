package dev.abgleich.adapter.out.postgres;

import static org.assertj.core.api.Assertions.assertThat;

import dev.abgleich.application.port.in.ImportSource;
import dev.abgleich.application.port.in.StatementReport;
import dev.abgleich.application.port.out.NewStatementImport;
import dev.abgleich.application.port.out.StatementFormat;
import dev.abgleich.domain.account.Iban;
import dev.abgleich.domain.invoice.Invoice;
import dev.abgleich.domain.invoice.InvoiceNumber;
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
import dev.abgleich.domain.statement.TransactionStatus;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class JdbcStatementReportRepositoryTest {

    private static final Iban ACCOUNT = Iban.of("CH9300762011623852957");
    private static final PaymentReference SCOR = new PaymentReference.Scor(CreditorReference.of("RF18539007547034"));
    private static final LocalDate SEP_15 = LocalDate.of(2026, 9, 15);
    private static final Instant NOW = Instant.parse("2026-09-16T06:00:00Z");

    private JdbcStatementReportRepository reports;

    @BeforeEach
    void setUp() {
        TestDatabase.emptied();
        reports = new JdbcStatementReportRepository(TestDatabase.dataSource());
    }

    @Test
    void report_shows_balances_transactions_and_their_confirmed_invoice() {
        UUID importId = storeStatement();
        Invoice invoice = Invoice.register(UUID.randomUUID(), InvoiceNumber.of("F-2026-0142"), ACCOUNT, "Keller GmbH",
                Money.chf("480.00"), SCOR, LocalDate.of(2026, 9, 30));
        new JdbcInvoiceRepository(TestDatabase.dataSource()).add(invoice);
        JdbcReconciliationRepository reconciliations =
                new JdbcReconciliationRepository(TestDatabase.dataSource(), TestDatabase.transactions());
        PaymentToMatch payment = reconciliations.findPendingCredits(ACCOUNT).stream()
                .filter(p -> p.reference().equals(SCOR)).findFirst().orElseThrow();
        ReconciliationDecision.AutoConfirmed decision =
                (ReconciliationDecision.AutoConfirmed) new Matcher().decide(payment, List.of(invoice), NOW);
        reconciliations.recordConfirmed(payment, decision.allocations(), List.of(invoice.withConfirmedPayment(payment.amount())),
                List.of());

        StatementReport report = reports.findReport(importId).orElseThrow();

        assertThat(report.account()).isEqualTo(ACCOUNT);
        assertThat(report.format()).isEqualTo(StatementFormat.CAMT053_V04);
        assertThat(report.closingBalance()).isEqualTo(new Balance(Money.chf("468.00"), SEP_15));
        assertThat(report.transactions()).hasSize(2);
        assertThat(report.transactions()).filteredOn(line -> line.status() == TransactionStatus.MATCHED)
                .singleElement().satisfies(line -> {
                    assertThat(line.invoiceNumber()).isEqualTo("F-2026-0142");
                    assertThat(line.rule()).isEqualTo(MatchRule.R1);
                    assertThat(line.allocationStatus()).isEqualTo(AllocationStatus.CONFIRMED);
                    assertThat(line.counterpartyName()).isEqualTo("Keller GmbH");
                    assertThat(line.explanation()).contains("F-2026-0142");
                });
        assertThat(report.transactions()).filteredOn(line -> line.direction() == Direction.DEBIT)
                .singleElement().satisfies(fee -> assertThat(fee.invoiceNumber()).isNull());
    }

    @Test
    void unknown_import_has_no_report() {
        assertThat(reports.findReport(UUID.randomUUID())).isEmpty();
    }

    private static UUID storeStatement() {
        TransactionDetail scor = new TransactionDetail(null, SCOR, "Rechnung 142", "Keller GmbH", null, null);
        Statement statement = new Statement(ACCOUNT, "STMT-1",
                new Balance(Money.chf("0.00"), SEP_15.minusDays(1)), new Balance(Money.chf("468.00"), SEP_15),
                List.of(
                        new StatementEntry(Money.chf("480.00"), Direction.CREDIT, SEP_15, null, "BNK-1", false, List.of(scor)),
                        new StatementEntry(Money.chf("12.00"), Direction.DEBIT, SEP_15, null, "BNK-2", false, List.of())));
        UUID id = UUID.randomUUID();
        new JdbcStatementImportRepository(TestDatabase.dataSource(), TestDatabase.transactions())
                .store(List.of(new NewStatementImport(id, ImportSource.WEB, StatementFormat.CAMT053_V04,
                        "b".repeat(64), "MSG-9", statement, NOW)));
        return id;
    }
}

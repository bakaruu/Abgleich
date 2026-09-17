package dev.abgleich.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;

import dev.abgleich.application.invoice.RegisterInvoiceCommand;
import dev.abgleich.application.invoice.port.in.RegisterInvoiceUseCase;
import dev.abgleich.application.reconciliation.port.in.ReconcileUseCase;
import dev.abgleich.application.reconciliation.port.in.ReconciliationRun;
import dev.abgleich.application.statement.ImportSource;
import dev.abgleich.application.statement.port.in.ImportStatementCommand;
import dev.abgleich.application.statement.port.in.ImportStatementUseCase;
import dev.abgleich.domain.account.Iban;
import dev.abgleich.domain.invoice.InvoiceNumber;
import dev.abgleich.domain.money.Money;
import dev.abgleich.domain.reference.PaymentReference;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/** Invoices, a real camt import and rule R1 against PostgreSQL. */
@AbgleichIntegrationTest
class ReconciliationIntegrationTest {

    private static final Iban QR_ACCOUNT = Iban.of("CH4431999123000889012");
    private static final Iban REGULAR_ACCOUNT = Iban.of("CH9300762011623852957");
    private static final String SCOR = "RF18539007547034";
    private static final byte[] CAMT = fixture("camt053/swiss-day-2026-09-15.xml");

    @Autowired
    RegisterInvoiceUseCase registerInvoice;

    @Autowired
    ImportStatementUseCase importStatement;

    @Autowired
    ReconcileUseCase reconcile;

    @Autowired
    JdbcTemplate jdbc;

    @BeforeEach
    void emptyDatabase() {
        jdbc.execute("truncate outbox_event, processed_message, allocation, bank_transaction, invoice, statement_import");
    }

    @Test
    void R1_confirms_every_payment_with_an_exact_reference_and_amount() {
        register("F-2026-0141", QR_ACCOUNT, "1250.00", "210000000003139471430009017");
        register("F-2026-0151", QR_ACCOUNT, "1000.00", "000000000000002026091500017");
        register("F-2026-0152", QR_ACCOUNT, "1000.00", "000000000000002026091500025");
        register("F-2026-0153", QR_ACCOUNT, "1000.00", "000000000000002026091500030");
        register("F-2026-0142", REGULAR_ACCOUNT, "480.00", SCOR);
        register("F-2026-0143", REGULAR_ACCOUNT, "480.00", null);
        register("F-2026-0144", REGULAR_ACCOUNT, "2000.00", null);
        importStatement.importStatement(command(CAMT));

        ReconciliationRun qr = reconcile.reconcilePending(QR_ACCOUNT);
        ReconciliationRun regular = reconcile.reconcilePending(REGULAR_ACCOUNT);

        assertThat(qr).as("QR payment and the batch of three; the fee is a debit")
                .isEqualTo(new ReconciliationRun(4, 4, 0, 0, 0, 0, 0));
        assertThat(regular).as("SCOR payment confirmed; \"Rechnung 143\" proposed by R4; Brunner matches no invoice")
                .isEqualTo(new ReconciliationRun(3, 1, 1, 1, 0, 0, 0));
        assertThat(jdbc.queryForList("select invoice_number from invoice where status = 'PAID' order by 1", String.class))
                .containsExactly("F-2026-0141", "F-2026-0142", "F-2026-0151", "F-2026-0152", "F-2026-0153");
        assertThat(jdbc.queryForObject("select count(*) from allocation where status = 'CONFIRMED' and rule = 'R1'",
                Integer.class)).isEqualTo(5);
    }

    @Test
    void running_reconciliation_again_changes_nothing() {
        register("F-2026-0142", REGULAR_ACCOUNT, "480.00", SCOR);
        importStatement.importStatement(command(CAMT));
        reconcile.reconcilePending(REGULAR_ACCOUNT);

        ReconciliationRun again = reconcile.reconcilePending(REGULAR_ACCOUNT);

        assertThat(again).isEqualTo(new ReconciliationRun(2, 0, 0, 2, 0, 0, 0));
        assertThat(jdbc.queryForObject("select count(*) from allocation", Integer.class)).isEqualTo(1);
    }

    @Test
    void B28_two_equal_invoices_go_to_review_and_stay_open() {
        register("F-2026-0199", REGULAR_ACCOUNT, "480.00", SCOR);
        register("F-2026-0200", REGULAR_ACCOUNT, "480.00", SCOR);
        importStatement.importStatement(command(CAMT));

        ReconciliationRun run = reconcile.reconcilePending(REGULAR_ACCOUNT);

        assertThat(run.sentToReview()).isEqualTo(1);
        assertThat(run.autoConfirmed()).isZero();
        assertThat(jdbc.queryForList("select distinct status from invoice", String.class)).containsExactly("OPEN");
        assertThat(jdbc.queryForList("select status from allocation", String.class))
                .containsExactly("PROPOSED", "PROPOSED");
    }

    @Test
    void B22_concurrent_allocation_same_invoice() throws Exception {
        register("F-2026-0142", REGULAR_ACCOUNT, "480.00", SCOR);
        importStatement.importStatement(command(CAMT));
        importStatement.importStatement(command(secondDayWithTheSameScorPayment()));
        assertThat(jdbc.queryForObject("select count(*) from bank_transaction where reference = ?", Integer.class, SCOR))
                .as("two payments of 480.00 for one invoice").isEqualTo(2);

        int runs = 6;
        CountDownLatch start = new CountDownLatch(1);
        Callable<ReconciliationRun> run = () -> {
            start.await();
            return reconcile.reconcilePending(REGULAR_ACCOUNT);
        };
        List<ReconciliationRun> results;
        try (ExecutorService pool = Executors.newFixedThreadPool(runs)) {
            List<Future<ReconciliationRun>> futures = IntStream.range(0, runs).mapToObj(i -> pool.submit(run)).toList();
            start.countDown();
            results = futures.stream().map(ReconciliationIntegrationTest::await).toList();
        }

        assertThat(results.stream().mapToInt(ReconciliationRun::autoConfirmed).sum()).isEqualTo(1);
        assertThat(jdbc.queryForMap("select status, paid_amount, version from invoice"))
                .containsEntry("status", "PAID")
                .containsEntry("version", 1L)
                .extractingByKey("paid_amount").isEqualTo(new java.math.BigDecimal("480.00"));
        assertThat(jdbc.queryForList("select status from bank_transaction where reference = ? order by status",
                String.class, SCOR)).as("the second payment goes to review as a possible duplicate (B31)")
                .containsExactly("MATCHED", "PROPOSED");
        assertThat(jdbc.queryForList("select status from allocation order by status", String.class))
                .containsExactly("CONFIRMED", "PROPOSED");
    }

    /** The same camt file one day later with new bank references: its SCOR payment is a second, real payment. */
    private static byte[] secondDayWithTheSameScorPayment() {
        return new String(CAMT, StandardCharsets.UTF_8)
                .replace("ABG-CH-20260915-0001", "ABG-CH-20260916-0001")
                .replace("BNK2026091500020", "BNK2026091600020")
                .getBytes(StandardCharsets.UTF_8);
    }

    private void register(String number, Iban account, String amount, String reference) {
        registerInvoice.register(new RegisterInvoiceCommand(InvoiceNumber.of(number), account, "Synthetic Customer AG",
                Money.chf(amount), PaymentReference.parse(reference), LocalDate.of(2026, 9, 30)));
    }

    private static ImportStatementCommand command(byte[] content) {
        return new ImportStatementCommand(ImportSource.WEB, () -> new ByteArrayInputStream(content));
    }

    private static ReconciliationRun await(Future<ReconciliationRun> future) {
        try {
            return future.get();
        } catch (Exception e) {
            throw new IllegalStateException("Reconciliation run failed", e);
        }
    }

    private static byte[] fixture(String name) {
        try (InputStream in = ReconciliationIntegrationTest.class.getResourceAsStream("/fixtures/" + name)) {
            return in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}

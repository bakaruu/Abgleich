package dev.abgleich.adapter.out.postgres;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import dev.abgleich.application.port.in.ImportSource;
import dev.abgleich.application.port.in.ImportedStatement;
import dev.abgleich.application.port.out.DuplicateImportException;
import dev.abgleich.application.port.out.NewStatementImport;
import dev.abgleich.application.port.out.StatementFormat;
import dev.abgleich.domain.account.Iban;
import dev.abgleich.domain.money.Direction;
import dev.abgleich.domain.money.Money;
import dev.abgleich.domain.reference.PaymentReference;
import dev.abgleich.domain.reference.QrReference;
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
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

class JdbcStatementImportRepositoryTest {

    private static final Iban SWISS = Iban.of("CH4431999123000889012");
    private static final Iban SPANISH = Iban.of("ES9121000418450200051332");
    private static final LocalDate SEP_14 = LocalDate.of(2026, 9, 14);
    private static final LocalDate SEP_15 = LocalDate.of(2026, 9, 15);
    private static final Instant RECEIVED = Instant.parse("2026-09-15T20:05:00Z");

    private JdbcStatementImportRepository repository;
    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        jdbc = TestDatabase.emptied();
        repository = new JdbcStatementImportRepository(TestDatabase.dataSource(), TestDatabase.transactions());
    }

    @Test
    void stores_the_import_and_one_unmatched_row_per_payment() {
        List<ImportedStatement> stored = repository.store(List.of(swissImport("a")));

        assertThat(stored).singleElement().satisfies(imported -> {
            assertThat(imported.newTransactions()).isEqualTo(5);
            assertThat(imported.knownTransactions()).isZero();
        });
        assertThat(jdbc.queryForList("select dedup_key, amount, status from bank_transaction order by dedup_key"))
                .extracting(row -> row.get("dedup_key") + " " + row.get("amount") + " " + row.get("status"))
                .containsExactly(
                        "BANK:BNK-1 1250.00 UNMATCHED",
                        "BANK:BNK-2#1 1000.00 UNMATCHED",
                        "BANK:BNK-2#2 1000.00 UNMATCHED",
                        "BANK:BNK-2#3 1000.00 UNMATCHED",
                        "BANK:BNK-3 12.00 UNMATCHED");
    }

    @Test
    void keeps_references_names_and_local_dates() {
        repository.store(List.of(swissImport("a")));

        Map<String, Object> qrPayment = jdbc.queryForMap(
                "select * from bank_transaction where dedup_key = 'BANK:BNK-1'");
        assertThat(qrPayment)
                .containsEntry("reference", "210000000003139471430009017")
                .containsEntry("counterparty_name", "Muster Handwerk GmbH")
                .containsEntry("direction", "CREDIT")
                .containsEntry("reversal", false);
        assertThat(jdbc.queryForObject("select booking_date from bank_transaction where dedup_key = 'BANK:BNK-1'",
                LocalDate.class)).isEqualTo(SEP_15);
    }

    @Test
    void B21_same_file_twice_is_refused_by_the_database() {
        repository.store(List.of(swissImport("a")));

        Throwable refused = catchThrowable(() -> repository.store(List.of(swissImport("a"))));

        assertThat(refused).isInstanceOf(DuplicateImportException.class).hasMessageContaining("uq_import_file");
        assertThat(count("statement_import")).isEqualTo(1);
        assertThat(count("bank_transaction")).isEqualTo(5);
    }

    @Test
    void B21_same_camt_message_in_a_different_file_is_refused() {
        repository.store(List.of(swissImport("a")));

        assertThat(catchThrowable(() -> repository.store(List.of(swissImport("b")))))
                .isInstanceOf(DuplicateImportException.class)
                .hasMessageContaining("uq_import_message");
    }

    @Test
    void B21_a_file_is_stored_completely_or_not_at_all() {
        repository.store(List.of(swissImport("a")));
        NewStatementImport newSpanish = spanishImport("c", "605.00");
        NewStatementImport duplicateSwiss = swissImport("a");

        assertThat(catchThrowable(() -> repository.store(List.of(newSpanish, duplicateSwiss))))
                .isInstanceOf(DuplicateImportException.class);

        assertThat(count("statement_import")).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from bank_transaction where account_iban = ?",
                Integer.class, SPANISH.value())).isZero();
    }

    @Test
    void B16_overlapping_file_stores_only_new_movements_and_keeps_identical_ones() {
        repository.store(List.of(spanishImport("c", "605.00", "605.00")));

        List<ImportedStatement> overlapping = repository.store(List.of(spanishImport("d", "605.00", "605.00", "605.00")));

        assertThat(overlapping.getFirst().newTransactions()).isEqualTo(1);
        assertThat(overlapping.getFirst().knownTransactions()).isEqualTo(2);
        assertThat(count("bank_transaction")).isEqualTo(3);
    }

    @Test
    void finds_previous_imports_by_file_or_message() {
        UUID id = repository.store(List.of(swissImport("a"))).getFirst().importId();

        List<ImportedStatement> byFile = repository.findPrevious(sha("a"), null, List.of());
        List<ImportedStatement> byMessage = repository.findPrevious(sha("z"), "MSG-1", List.of(SWISS));

        assertThat(byFile).singleElement().satisfies(previous -> {
            assertThat(previous.importId()).isEqualTo(id);
            assertThat(previous.account()).isEqualTo(SWISS);
            assertThat(previous.openingBalance()).isEqualTo(new Balance(Money.chf("10000.00"), SEP_14));
            assertThat(previous.closingBalance()).isEqualTo(new Balance(Money.chf("14238.00"), SEP_15));
            assertThat(previous.newTransactions()).isEqualTo(5);
        });
        assertThat(byMessage).extracting(ImportedStatement::importId).containsExactly(id);
        assertThat(repository.findPrevious(sha("z"), "MSG-1", List.of(SPANISH))).isEmpty();
    }

    @Test
    void long_texts_are_cut_to_the_column_size() {
        String longText = "TRANSF ".repeat(100);

        repository.store(List.of(spanishImportWithText("e", longText)));

        String stored = jdbc.queryForObject("select remittance_text from bank_transaction", String.class);
        assertThat(stored).hasSize(500);
    }

    @Test
    void B12_notification_fills_empty_details_and_never_creates_transactions() {
        repository.store(List.of(swissImport("a")));
        TransactionDetail notified = new TransactionDetail(null, null, "Hotelrechnung September",
                "Other name ignored", "E2E-77", null, Money.chf("0.00"));
        dev.abgleich.domain.statement.Notification notification = new dev.abgleich.domain.statement.Notification(SWISS,
                "N-1", List.of(
                        new StatementEntry(Money.chf("1250.00"), Direction.CREDIT, SEP_15, null, "BNK-1", false,
                                List.of(notified)),
                        new StatementEntry(Money.chf("5.00"), Direction.DEBIT, SEP_15, null, "BNK-UNKNOWN", false,
                                List.of())));

        List<dev.abgleich.application.port.in.EnrichedNotification> result = repository.enrich(List.of(notification));
        List<dev.abgleich.application.port.in.EnrichedNotification> again = repository.enrich(List.of(notification));

        assertThat(result).singleElement().satisfies(enriched -> {
            assertThat(enriched.enriched()).isEqualTo(1);
            assertThat(enriched.unknown()).isEqualTo(1);
        });
        assertThat(again.getFirst().enriched()).as("nothing left to add").isZero();
        assertThat(again.getFirst().alreadyComplete()).isEqualTo(1);
        assertThat(count("bank_transaction")).isEqualTo(5);
        assertThat(jdbc.queryForMap("select remittance_text, counterparty_name, end_to_end_id, charges from bank_transaction where dedup_key = 'BANK:BNK-1'"))
                .containsEntry("remittance_text", "Hotelrechnung September")
                .as("filled fields are never overwritten").containsEntry("counterparty_name", "Muster Handwerk GmbH")
                .containsEntry("end_to_end_id", "E2E-77");
    }

    @Test
    void B39_database_errors_never_reach_the_core_as_spring_exceptions() {
        repository.store(List.of(swissImport("a")));

        Throwable refused = catchThrowable(() -> repository.store(List.of(swissImport("a"))));

        assertThat(refused).isNotInstanceOf(DataAccessException.class);
    }

    private int count(String table) {
        return jdbc.queryForObject("select count(*) from " + table, Integer.class);
    }

    /** Same shape as the camt fixture: a QR payment, a batch of three and a fee. */
    private static NewStatementImport swissImport(String fileSeed) {
        TransactionDetail qr = new TransactionDetail(null,
                new PaymentReference.Qrr(QrReference.of("210000000003139471430009017")), null,
                "Muster Handwerk GmbH", null, null);
        List<TransactionDetail> batch = List.of(
                new TransactionDetail(Money.chf("1000.00"), null, null, null, null, null),
                new TransactionDetail(Money.chf("1000.00"), null, null, null, null, null),
                new TransactionDetail(Money.chf("1000.00"), null, null, null, null, null));
        Statement statement = new Statement(SWISS, "STMT-QR",
                new Balance(Money.chf("10000.00"), SEP_14), new Balance(Money.chf("14238.00"), SEP_15),
                List.of(
                        new StatementEntry(Money.chf("1250.00"), Direction.CREDIT, SEP_15, null, "BNK-1", false, List.of(qr)),
                        new StatementEntry(Money.chf("3000.00"), Direction.CREDIT, SEP_15, null, "BNK-2", false, batch),
                        new StatementEntry(Money.chf("12.00"), Direction.DEBIT, SEP_15, null, "BNK-3", false, List.of())));
        return new NewStatementImport(UUID.randomUUID(), ImportSource.REST, StatementFormat.CAMT053_V04,
                sha(fileSeed), "MSG-1", statement, RECEIVED);
    }

    private static NewStatementImport spanishImport(String fileSeed, String... amounts) {
        Money total = Money.eur("0.00");
        List<StatementEntry> entries = new java.util.ArrayList<>();
        for (String amount : amounts) {
            TransactionDetail concept = new TransactionDetail(null, null, "PAGO FACTURAS 91 Y 92", null, null, null);
            entries.add(new StatementEntry(Money.eur(amount), Direction.CREDIT, SEP_15, null, null, false, List.of(concept)));
            total = total.add(Money.eur(amount));
        }
        Statement statement = new Statement(SPANISH, null,
                new Balance(Money.eur("0.00"), SEP_15), new Balance(total, SEP_15), entries);
        return new NewStatementImport(UUID.randomUUID(), ImportSource.WEB, StatementFormat.NORMA43,
                sha(fileSeed), null, statement, RECEIVED);
    }

    private static NewStatementImport spanishImportWithText(String fileSeed, String text) {
        TransactionDetail concept = new TransactionDetail(null, null, text, null, null, null);
        Statement statement = new Statement(SPANISH, null, new Balance(Money.eur("0.00"), SEP_15),
                new Balance(Money.eur("10.00"), SEP_15),
                List.of(new StatementEntry(Money.eur("10.00"), Direction.CREDIT, SEP_15, null, null, false, List.of(concept))));
        return new NewStatementImport(UUID.randomUUID(), ImportSource.WEB, StatementFormat.NORMA43,
                sha(fileSeed), null, statement, RECEIVED);
    }

    private static String sha(String seed) {
        return seed.repeat(64).substring(0, 64);
    }
}

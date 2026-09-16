package dev.abgleich.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import dev.abgleich.application.port.in.ImportResult;
import dev.abgleich.application.port.in.ImportResult.Outcome;
import dev.abgleich.application.port.in.ImportSource;
import dev.abgleich.application.port.in.ImportStatementCommand;
import dev.abgleich.application.port.in.ImportStatementUseCase;
import dev.abgleich.application.port.in.ImportedStatement;
import dev.abgleich.application.port.out.StatementFormat;
import dev.abgleich.domain.statement.InvalidStatementException;
import dev.abgleich.domain.statement.InvalidStatementException.Reason;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
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

/** The whole import against a real PostgreSQL: parsers, use case, repository and constraints. */
@AbgleichIntegrationTest
class ImportStatementIntegrationTest {

    private static final byte[] CAMT = fixture("swiss-day-2026-09-15.xml");
    private static final byte[] NORMA43 = fixture("spain-two-accounts-2026-09-15.n43");

    @Autowired
    ImportStatementUseCase importStatement;

    @Autowired
    JdbcTemplate jdbc;

    @BeforeEach
    void emptyDatabase() {
        jdbc.execute("truncate allocation, bank_transaction, invoice, statement_import");
    }

    @Test
    void imports_a_swiss_camt_file() {
        ImportResult result = importStatement.importStatement(command(CAMT));

        assertThat(result.outcome()).isEqualTo(Outcome.IMPORTED);
        assertThat(result.format()).isEqualTo(StatementFormat.CAMT053_V04);
        assertThat(result.statements()).extracting(ImportedStatement::newTransactions)
                .as("QR payment, batch of three and fee; three credits, pending entry skipped")
                .containsExactly(5, 3);
    }

    @Test
    void B19_imports_a_spanish_norma43_file_keeping_latin1_names() {
        ImportResult result = importStatement.importStatement(command(NORMA43));

        assertThat(result.format()).isEqualTo(StatementFormat.NORMA43);
        assertThat(jdbc.queryForList("select remittance_text from bank_transaction", String.class))
                .contains("TRANSF JOSE MUÑOZ ÁLVAREZ FRA 90");
    }

    @Test
    void B21_importing_the_same_file_again_changes_nothing() {
        ImportResult first = importStatement.importStatement(command(CAMT));

        ImportResult second = importStatement.importStatement(command(CAMT));

        assertThat(second.outcome()).isEqualTo(Outcome.ALREADY_IMPORTED);
        assertThat(second.statements()).extracting(ImportedStatement::importId)
                .containsExactlyInAnyOrderElementsOf(first.statements().stream().map(ImportedStatement::importId).toList());
        assertThat(count("statement_import")).isEqualTo(2);
        assertThat(count("bank_transaction")).isEqualTo(8);
    }

    @Test
    void B21_concurrent_duplicate_upload() throws Exception {
        int uploads = 8;
        CountDownLatch start = new CountDownLatch(1);
        Callable<ImportResult> upload = () -> {
            start.await();
            return importStatement.importStatement(command(CAMT));
        };

        List<ImportResult> results;
        try (ExecutorService pool = Executors.newFixedThreadPool(uploads)) {
            List<Future<ImportResult>> futures = IntStream.range(0, uploads).mapToObj(i -> pool.submit(upload)).toList();
            start.countDown();
            results = futures.stream().map(ImportStatementIntegrationTest::await).toList();
        }

        assertThat(results).extracting(ImportResult::outcome)
                .containsOnlyOnce(Outcome.IMPORTED)
                .contains(Outcome.ALREADY_IMPORTED);
        assertThat(count("statement_import")).isEqualTo(2);
        assertThat(count("bank_transaction")).isEqualTo(8);
    }

    @Test
    void B16_overlapping_file_with_the_same_movements_stores_nothing_new() {
        importStatement.importStatement(command(NORMA43));
        byte[] sameMovementsOtherBytes = (new String(NORMA43, StandardCharsets.ISO_8859_1) + "\r\n")
                .getBytes(StandardCharsets.ISO_8859_1);

        ImportResult overlapping = importStatement.importStatement(command(sameMovementsOtherBytes));

        assertThat(overlapping.outcome()).as("a different file").isEqualTo(Outcome.IMPORTED);
        assertThat(overlapping.statements()).extracting(ImportedStatement::newTransactions).containsOnly(0);
        assertThat(overlapping.statements()).extracting(ImportedStatement::knownTransactions).containsExactly(5, 1);
        assertThat(count("bank_transaction")).as("both identical 605,00 € transfers kept once each").isEqualTo(6);
    }

    @Test
    void B26_import_commits_before_matching() {
        importStatement.importStatement(command(CAMT));

        // JdbcTemplate runs outside the import transaction: it only sees committed rows.
        assertThat(jdbc.queryForList("select distinct status from bank_transaction", String.class))
                .containsExactly("UNMATCHED");
        assertThat(count("allocation")).isZero();
    }

    @Test
    void rejected_file_stores_nothing() {
        byte[] unbalanced = new String(CAMT, StandardCharsets.UTF_8)
                .replaceFirst("14238.00", "14238.01").getBytes(StandardCharsets.UTF_8);

        InvalidStatementException rejected = catchThrowableOfType(InvalidStatementException.class,
                () -> importStatement.importStatement(command(unbalanced)));

        assertThat(rejected.reason()).isEqualTo(Reason.UNBALANCED);
        assertThat(count("statement_import")).isZero();
        assertThat(count("bank_transaction")).isZero();
    }

    private int count(String table) {
        return jdbc.queryForObject("select count(*) from " + table, Integer.class);
    }

    private static ImportStatementCommand command(byte[] content) {
        return new ImportStatementCommand(ImportSource.WEB, () -> new ByteArrayInputStream(content));
    }

    private static ImportResult await(Future<ImportResult> future) {
        try {
            return future.get();
        } catch (Exception e) {
            throw new IllegalStateException("Upload failed", e);
        }
    }

    private static byte[] fixture(String name) {
        try (InputStream in = ImportStatementIntegrationTest.class.getResourceAsStream("/fixtures/" + name)) {
            return in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}

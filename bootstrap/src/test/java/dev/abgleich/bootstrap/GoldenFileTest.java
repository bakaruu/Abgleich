package dev.abgleich.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;

import dev.abgleich.application.port.in.ExampleDataUseCase;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Files plus invoices in, reconciliation out: the whole example through parsers, database and every rule
 * is compared with a reviewed text file. A change in behaviour shows up as a readable diff.
 *
 * <p>After an intended change: {@code ./gradlew :bootstrap:test --tests '*GoldenFileTest' -Pgolden.update=true},
 * then review the diff of {@code src/test/resources/golden/example-reconciliation.txt} before committing it.
 */
@AbgleichIntegrationTest
class GoldenFileTest {

    private static final String GOLDEN = "golden/example-reconciliation.txt";

    @Autowired
    ExampleDataUseCase examples;

    @Autowired
    JdbcTemplate jdbc;

    @BeforeEach
    void emptyDatabase() {
        jdbc.execute("truncate outbox_event, processed_message, allocation, bank_transaction, invoice, statement_import");
    }

    @Test
    void example_reconciliation_matches_the_golden_file() throws IOException {
        examples.load();

        String actual = ReconciliationReport.render(jdbc);

        if (Boolean.getBoolean("golden.update")) {
            Path file = Path.of("src/test/resources", GOLDEN);
            Files.createDirectories(file.getParent());
            Files.writeString(file, actual, StandardCharsets.UTF_8);
            return;
        }
        Path actualCopy = Path.of("build/golden/example-reconciliation.actual.txt");
        Files.createDirectories(actualCopy.getParent());
        Files.writeString(actualCopy, actual, StandardCharsets.UTF_8);
        assertThat(actual).as("differs from %s; see %s", GOLDEN, actualCopy).isEqualTo(golden());
    }

    private static String golden() throws IOException {
        try (InputStream in = GoldenFileTest.class.getResourceAsStream("/" + GOLDEN)) {
            assertThat(in).as("missing %s: run with -Pgolden.update=true and review the file", GOLDEN).isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}

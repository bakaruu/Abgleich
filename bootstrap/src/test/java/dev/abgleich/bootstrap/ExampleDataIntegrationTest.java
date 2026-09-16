package dev.abgleich.bootstrap;

import static dev.abgleich.bootstrap.Browser.map;
import static org.assertj.core.api.Assertions.assertThat;

import dev.abgleich.adapter.out.synthetic.SyntheticDataGenerator;
import dev.abgleich.adapter.out.synthetic.SyntheticDataset;
import dev.abgleich.adapter.out.synthetic.SyntheticDataset.Expected;
import dev.abgleich.application.port.in.ExampleDataUseCase;
import dev.abgleich.application.port.in.ExampleDataUseCase.ExampleLoaded;
import dev.abgleich.application.port.in.ExampleDataUseCase.FileProcessed;
import dev.abgleich.application.port.in.ImportResult;
import dev.abgleich.application.port.in.ReconciliationRun;
import java.net.http.HttpResponse;
import java.util.Arrays;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;

/** The synthetic example through the real parsers, database and rules: its labels must come true. */
@AbgleichIntegrationTest
class ExampleDataIntegrationTest {

    private static final SyntheticDataset DATASET = SyntheticDataGenerator.defaultDataset();

    @Value("${local.server.port}")
    int port;

    @Autowired
    ExampleDataUseCase examples;

    @Autowired
    JdbcTemplate jdbc;

    @BeforeEach
    void emptyDatabase() {
        jdbc.execute("truncate allocation, bank_transaction, invoice, statement_import");
    }

    @Test
    void B27_only_the_payments_labelled_R1_are_confirmed_automatically() {
        ExampleLoaded loaded = examples.load();

        assertThat(loaded.invoicesRegistered()).isEqualTo(DATASET.invoices().size());
        assertThat(loaded.files()).extracting(file -> file.processed().imported().outcome())
                .containsOnly(ImportResult.Outcome.IMPORTED);
        int autoConfirmed = loaded.files().stream()
                .flatMap(file -> file.processed().reconciliations().stream())
                .mapToInt(ReconciliationRun::autoConfirmed).sum();
        assertThat(autoConfirmed).isEqualTo(DATASET.count(Expected.R1_AUTO_CONFIRM));
        assertThat(jdbc.queryForList("""
                select i.invoice_number from allocation a join invoice i on i.id = a.invoice_id
                 where a.status = 'CONFIRMED' order by 1
                """, String.class))
                .containsExactlyElementsOf(DATASET.labels().stream()
                        .filter(label -> label.expected() == Expected.R1_AUTO_CONFIRM)
                        .map(SyntheticDataset.Label::invoiceNumber).sorted().toList());
    }

    @Test
    void B16_both_identical_spanish_transfers_are_stored() {
        examples.load();

        assertThat(jdbc.queryForObject("select count(*) from bank_transaction where amount = 605.00 and currency = 'EUR'",
                Integer.class)).isEqualTo(2);
    }

    @Test
    void B21_loading_the_example_twice_changes_nothing() {
        examples.load();
        int transactions = count("bank_transaction");

        ExampleLoaded again = examples.load();

        assertThat(again.invoicesRegistered()).isZero();
        assertThat(again.invoicesAlreadyPresent()).isEqualTo(DATASET.invoices().size());
        assertThat(again.files()).extracting(FileProcessed::processed)
                .extracting(processed -> processed.imported().outcome())
                .containsOnly(ImportResult.Outcome.ALREADY_IMPORTED);
        assertThat(count("bank_transaction")).isEqualTo(transactions);
        assertThat(jdbc.queryForObject("select count(*) from allocation where status = 'CONFIRMED'", Integer.class))
                .isEqualTo((int) DATASET.count(Expected.R1_AUTO_CONFIRM));
    }

    @Test
    void load_example_button_shows_both_files_with_their_matches() {
        Browser browser = new Browser(port);

        HttpResponse<String> response = browser.upload("/examples", new byte[0], Map.of(),
                map("HX-Request", "true", "X-CSRF-TOKEN", browser.csrfToken()));

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body())
                .contains("Example loaded.")
                .contains("16 invoices registered, 0 already present.")
                .contains("example-ch-camt053-20260915.xml")
                .contains("example-es-norma43-20260915.n43")
                .contains("Matched R1")
                .contains("JOSÉ MUÑOZ ÁLVAREZ");
    }

    @Test
    void example_files_can_be_downloaded_byte_for_byte() {
        Browser browser = new Browser(port);
        String page = browser.get("/").body();

        assertThat(page).contains("/examples/files/example-es-norma43-20260915.n43");
        HttpResponse<byte[]> download = browser.getBytes("/examples/files/example-es-norma43-20260915.n43");
        assertThat(download.statusCode()).isEqualTo(200);
        assertThat(download.headers().firstValue("Content-Disposition")).hasValueSatisfying(value ->
                assertThat(value).startsWith("attachment"));
        assertThat(Arrays.equals(download.body(), DATASET.files().get(1).content())).isTrue();
        assertThat(browser.get("/examples/files/..%2F..%2Fetc%2Fpasswd").statusCode()).isIn(400, 404);
    }

    private int count(String table) {
        return jdbc.queryForObject("select count(*) from " + table, Integer.class);
    }
}

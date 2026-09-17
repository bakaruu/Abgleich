package dev.abgleich.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;

import dev.abgleich.application.example.port.in.ExampleDataUseCase;
import dev.abgleich.application.example.port.in.ExampleDataUseCase.ExampleLoaded;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;

/** The Summary screen, its API and the Prometheus metrics after loading the example. */
@AbgleichIntegrationTest
class SummaryAndMetricsTest {

    @Value("${local.server.port}")
    int port;

    @Autowired
    ExampleDataUseCase examples;

    @Autowired
    JdbcTemplate jdbc;

    private Browser browser;

    @BeforeEach
    void setUp() {
        jdbc.execute("truncate outbox_event, processed_message, allocation, bank_transaction, invoice, statement_import");
        browser = new Browser(port);
    }

    @Test
    void summary_screen_and_api_show_the_same_figures_as_the_database() {
        examples.load();
        long credits = count("select count(*) from bank_transaction where direction = 'CREDIT'");
        long waiting = count("select count(*) from bank_transaction where direction = 'CREDIT' and status = 'PROPOSED'");

        HttpResponse<String> page = browser.get("/summary");
        HttpResponse<String> api = browser.get("/api/v1/reports/summary");

        assertThat(page.statusCode()).isEqualTo(200);
        assertThat(page.body()).contains("Settled automatically").contains(" of " + credits + " payments")
                .contains("<span class=\"rule\">R6</span>")
                .doesNotContain("Public demo with synthetic data");
        assertThat(api.statusCode()).isEqualTo(200);
        assertThat(api.body()).contains("\"credits\":" + credits).contains("\"waitingForReview\":" + waiting)
                .contains("\"autoReconciledPercent\":\"").contains("{\"source\":\"EXAMPLE\",\"files\":2}");
    }

    @Test
    void loading_one_country_registers_only_its_invoices_and_statement() {
        ExampleLoaded swiss = examples.load("CH");

        assertThat(swiss.files()).extracting(file -> file.file().country()).containsExactly("CH");
        assertThat(jdbc.queryForList("select distinct left(creditor_iban, 2) from invoice", String.class))
                .containsExactly("CH");
        assertThat(swiss.invoicesRegistered()).isEqualTo(count("select count(*) from invoice"));
    }

    @Test
    void prometheus_exposes_business_metrics() {
        examples.load();

        String metrics = browser.get("/actuator/prometheus").body();

        assertThat(metrics)
                .containsPattern("abgleich_imports_total\\{[^}]*format=\"camt053_v04\"[^}]*result=\"imported\"[^}]*source=\"example\"[^}]*} [1-9]")
                .contains("abgleich_import_duration_seconds_count{")
                .containsPattern("abgleich_review_queue_size\\{[^}]*} [1-9]")
                .containsPattern("abgleich_allocations\\{[^}]*outcome=\"auto_confirmed\"[^}]*rule=\"R1\"[^}]*} [1-9]")
                .contains("abgleich_unmatched_amount{")
                .contains("abgleich_outbox_pending{")
                .contains("abgleich_auto_reconciliation_ratio{");
    }

    private long count(String sql) {
        return jdbc.queryForObject(sql, Long.class);
    }
}

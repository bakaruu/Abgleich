package dev.abgleich.bootstrap;

import static dev.abgleich.bootstrap.Browser.map;
import static org.assertj.core.api.Assertions.assertThat;

import dev.abgleich.application.port.in.RegisterInvoiceCommand;
import dev.abgleich.application.port.in.RegisterInvoiceUseCase;
import dev.abgleich.domain.account.Iban;
import dev.abgleich.domain.invoice.InvoiceNumber;
import dev.abgleich.domain.money.Money;
import dev.abgleich.domain.reference.PaymentReference;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;

/** The "Upload statement" screen through the real HTTP stack: security filters, multipart, Thymeleaf. */
@AbgleichIntegrationTest
class WebUploadTest {

    private static final byte[] CAMT = fixture("camt053/swiss-day-2026-09-15.xml");
    private static final Map<String, String> HTMX = map("HX-Request", "true");

    @Value("${local.server.port}")
    int port;

    @Autowired
    RegisterInvoiceUseCase registerInvoice;

    @Autowired
    JdbcTemplate jdbc;

    private Browser browser;

    @BeforeEach
    void setUp() {
        jdbc.execute("truncate allocation, bank_transaction, invoice, statement_import");
        browser = new Browser(port);
    }

    @Test
    void B32_upload_page_is_served_with_a_strict_content_security_policy() {
        HttpResponse<String> page = browser.get("/");

        assertThat(page.statusCode()).isEqualTo(200);
        assertThat(page.headers().firstValue("Content-Security-Policy")).hasValueSatisfying(csp -> assertThat(csp)
                .contains("script-src 'self'")
                .contains("object-src 'none'")
                .contains("frame-ancestors 'none'")
                .doesNotContain("unsafe-inline")
                .doesNotContain("unsafe-eval"));
        assertThat(page.headers().firstValue("X-Content-Type-Options")).hasValue("nosniff");
        assertThat(page.body()).contains("<meta name=\"csrf-token\"").contains("name=\"_csrf\"");
    }

    @Test
    void scripts_and_styles_referenced_by_the_page_are_served_from_the_same_origin() {
        String page = browser.get("/").body();
        java.util.regex.Matcher assets = java.util.regex.Pattern.compile("(?:src|href)=\"(/[^\"]+\\.(?:js|css))\"").matcher(page);

        int found = 0;
        while (assets.find()) {
            found++;
            assertThat(browser.get(assets.group(1)).statusCode()).as(assets.group(1)).isEqualTo(200);
        }
        assertThat(found).as("htmx, app.js and app.css").isEqualTo(3);
    }

    @Test
    void B35_post_without_csrf_is_forbidden() {
        HttpResponse<String> response = browser.upload("/statements", CAMT, Map.of(), HTMX);

        assertThat(response.statusCode()).isEqualTo(403);
        assertThat(count("statement_import")).isZero();
    }

    @Test
    void B35_post_with_a_token_from_another_session_is_forbidden() {
        String foreignToken = new Browser(port).csrfToken();
        browser.csrfToken();

        HttpResponse<String> response = browser.upload("/statements", CAMT, Map.of(),
                map("HX-Request", "true", "X-CSRF-TOKEN", foreignToken));

        assertThat(response.statusCode()).isEqualTo(403);
    }

    @Test
    void htmx_upload_shows_validated_balances_and_the_r1_match() {
        registerInvoice.register(new RegisterInvoiceCommand(InvoiceNumber.of("F-2026-0142"),
                Iban.of("CH9300762011623852957"), "Alpenblick Hotel SA", Money.chf("480.00"),
                PaymentReference.parse("RF18539007547034"), LocalDate.of(2026, 9, 30)));

        HttpResponse<String> response = browser.upload("/statements", CAMT, Map.of(),
                map("HX-Request", "true", "X-CSRF-TOKEN", browser.csrfToken()));

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body())
                .as("only the fragment is returned for htmx")
                .doesNotContain("<html")
                .contains("Imported.")
                .contains("Balances add up")
                .contains("CH93 **** **** **** 5295 7")
                .contains("Matched R1")
                .contains("F-2026-0142")
                .contains("Zürcher Bäckerei Löwen");
    }

    @Test
    void form_without_javascript_sends_the_token_as_a_field_and_gets_the_whole_page() {
        HttpResponse<String> response = browser.upload("/statements", CAMT, map("_csrf", browser.csrfToken()), Map.of());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("<html").contains("Imported.");
    }

    @Test
    void B21_uploading_the_same_file_again_changes_nothing() {
        String token = browser.csrfToken();
        browser.upload("/statements", CAMT, Map.of(), map("HX-Request", "true", "X-CSRF-TOKEN", token));

        HttpResponse<String> again = browser.upload("/statements", CAMT, Map.of(),
                map("HX-Request", "true", "X-CSRF-TOKEN", token));

        assertThat(again.body()).contains("Already imported.");
        assertThat(count("bank_transaction")).isEqualTo(8);
    }

    @Test
    void rejected_file_shows_the_reason_and_stores_nothing() {
        byte[] unbalanced = new String(CAMT, StandardCharsets.UTF_8)
                .replaceFirst("14238.00", "14238.01").getBytes(StandardCharsets.UTF_8);

        HttpResponse<String> response = browser.upload("/statements", unbalanced, Map.of(),
                map("HX-Request", "true", "X-CSRF-TOKEN", browser.csrfToken()));

        assertThat(response.statusCode()).isEqualTo(422);
        assertThat(response.body()).contains("File rejected.").contains("closing balance is CHF 14238.01");
        assertThat(count("statement_import")).isZero();
    }

    @Test
    void B32_remittance_text_is_escaped() {
        byte[] malicious = new String(CAMT, StandardCharsets.UTF_8)
                .replace("<Nm>Muster Handwerk GmbH</Nm>", "<Nm>&lt;script&gt;alert(1)&lt;/script&gt;</Nm>")
                .replace("<Ustrd>Rechnung 143</Ustrd>", "<Ustrd>&lt;img src=x onerror=alert(2)&gt;</Ustrd>")
                .getBytes(StandardCharsets.UTF_8);

        HttpResponse<String> response = browser.upload("/statements", malicious, Map.of(),
                map("HX-Request", "true", "X-CSRF-TOKEN", browser.csrfToken()));

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body())
                .contains("&lt;script&gt;alert(1)&lt;/script&gt;")
                .contains("&lt;img src=x onerror=alert(2)&gt;")
                .doesNotContain("<script>alert(1)")
                .doesNotContain("<img src=x");
    }

    @Test
    void B42_oversized_upload_is_rejected() {
        byte[] tooLarge = new byte[21 * 1024 * 1024];

        HttpResponse<String> response = browser.upload("/statements", tooLarge, Map.of(),
                map("HX-Request", "true", "X-CSRF-TOKEN", browser.csrfToken()));

        assertThat(response.statusCode()).isEqualTo(413);
        assertThat(response.body()).contains("larger than 20 MB");
        assertThat(count("statement_import")).isZero();
    }

    private int count(String table) {
        return jdbc.queryForObject("select count(*) from " + table, Integer.class);
    }

    private static byte[] fixture(String name) {
        try (InputStream in = WebUploadTest.class.getResourceAsStream("/fixtures/" + name)) {
            return in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}

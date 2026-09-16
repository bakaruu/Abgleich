package dev.abgleich.bootstrap;

import static dev.abgleich.bootstrap.Browser.map;
import static org.assertj.core.api.Assertions.assertThat;

import dev.abgleich.application.port.in.ImportSource;
import dev.abgleich.application.port.in.ImportStatementCommand;
import dev.abgleich.application.port.in.ProcessStatementUseCase;
import dev.abgleich.application.port.in.RegisterInvoiceCommand;
import dev.abgleich.application.port.in.RegisterInvoiceUseCase;
import dev.abgleich.application.port.in.ReviewQueueQuery;
import dev.abgleich.application.port.in.ReviewQueueQuery.ReviewItem;
import dev.abgleich.domain.account.Iban;
import dev.abgleich.domain.invoice.InvoiceNumber;
import dev.abgleich.domain.money.Money;
import dev.abgleich.domain.reference.PaymentReference;
import java.io.ByteArrayInputStream;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;

/** Reviewing proposals and managing invoices through the web UI and the JSON API, end to end. */
@AbgleichIntegrationTest
class ReviewFlowTest {

    private static final Iban ACCOUNT = Iban.of("ES9121000418450200051332");
    private static final String MALICIOUS_TEXT = "<script>alert(1)</script> TRANSF FRA 87";

    @Value("${local.server.port}")
    int port;

    @Autowired
    RegisterInvoiceUseCase registerInvoice;

    @Autowired
    ProcessStatementUseCase processStatement;

    @Autowired
    ReviewQueueQuery reviewQueue;

    @Autowired
    JdbcTemplate jdbc;

    private Browser browser;
    private UUID invoiceId;

    @BeforeEach
    void setUp() {
        jdbc.execute("truncate outbox_event, processed_message, allocation, bank_transaction, invoice, statement_import");
        browser = new Browser(port);
        invoiceId = registerInvoice.register(new RegisterInvoiceCommand(InvoiceNumber.of("FV-2026-0087"), ACCOUNT,
                "Talleres Ruiz SL", Money.eur("1815.00"), PaymentReference.none(), LocalDate.of(2026, 9, 30)));
        upload(csv("1815.00", MALICIOUS_TEXT, "CSV-0001"));
    }

    @Test
    void B32_review_queue_escapes_bank_text() {
        HttpResponse<String> page = browser.get("/review");

        assertThat(page.statusCode()).isEqualTo(200);
        assertThat(page.body())
                .contains("&lt;script&gt;alert(1)&lt;/script&gt; TRANSF FRA 87")
                .doesNotContain("<script>alert(1)")
                .contains("FV-2026-0087")
                .contains("R4");
    }

    @Test
    void B35_decision_without_csrf_token_is_forbidden() {
        ReviewItem item = pending();

        HttpResponse<String> response = browser.postForm(confirmPath(item), map("version", "" + item.version()),
                map("HX-Request", "true"));

        assertThat(response.statusCode()).isEqualTo(403);
        assertThat(status("bank_transaction")).isEqualTo("PROPOSED");
    }

    @Test
    void B33_double_confirm_in_the_browser_is_idempotent() {
        ReviewItem item = pending();
        Map<String, String> headers = map("HX-Request", "true", "X-CSRF-TOKEN", browser.csrfToken());

        HttpResponse<String> first = browser.postForm(confirmPath(item), map("version", "" + item.version()), headers);
        HttpResponse<String> second = browser.postForm(confirmPath(item), map("version", "" + item.version()), headers);

        assertThat(first.statusCode()).isEqualTo(200);
        assertThat(first.body()).contains("Confirmed: EUR 1815.00 allocated to FV-2026-0087.").doesNotContain("<html");
        assertThat(second.statusCode()).isEqualTo(200);
        assertThat(second.body()).contains("This proposal is already confirmed.");
        assertThat(jdbc.queryForObject("select count(*) from allocation where status = 'CONFIRMED'", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select status from invoice", String.class)).isEqualTo("PAID");
    }

    @Test
    void B34_stale_decision_is_refused_with_a_message() {
        ReviewItem item = pending();
        String token = browser.csrfToken();
        browser.postForm(rejectPath(item), map("version", "" + item.version(), "reason", "Different customer"),
                map("HX-Request", "true", "X-CSRF-TOKEN", token));

        HttpResponse<String> late = browser.postForm(confirmPath(item), map("version", "" + item.version()),
                map("HX-Request", "true", "X-CSRF-TOKEN", token));

        assertThat(late.statusCode()).isEqualTo(409);
        assertThat(late.body()).contains("Someone else already decided on this payment. Reload to see its current state.");
        assertThat(jdbc.queryForObject("select status from invoice", String.class)).isEqualTo("OPEN");
    }

    @Test
    void rejection_is_kept_in_the_invoice_history_and_not_proposed_again() {
        ReviewItem item = pending();
        String token = browser.csrfToken();

        HttpResponse<String> response = browser.postForm(rejectPath(item),
                map("version", "" + item.version(), "reason", "Different customer"), map("X-CSRF-TOKEN", token));

        assertThat(response.statusCode()).as("without htmx the browser is redirected back").isEqualTo(302);
        assertThat(status("bank_transaction")).isEqualTo("UNMATCHED");
        assertThat(browser.get("/invoices/" + invoiceId).body()).contains("REJECTED").contains("Different customer");
        processStatement.process(command(csv("1815.00", MALICIOUS_TEXT, "CSV-0001")));
        assertThat(reviewQueue.pendingCount()).as("a new reconciliation run does not propose it again").isZero();
    }

    @Test
    void invoices_can_be_registered_and_cancelled_from_the_browser() {
        String token = browser.csrfToken();

        HttpResponse<String> created = browser.postForm("/invoices", map("invoiceNumber", "FV-2026-0099",
                "creditorIban", "ES91 2100 0418 4502 0005 1332", "debtorName", "Pinturas Sol SA", "amount", "250.00",
                "currency", "EUR", "reference", "", "dueDate", "2026-10-31"), map("X-CSRF-TOKEN", token));
        assertThat(created.statusCode()).isEqualTo(302);
        String location = created.headers().firstValue("Location").orElseThrow();
        UUID id = UUID.fromString(location.substring(location.lastIndexOf('/') + 1));

        HttpResponse<String> cancelled = browser.postForm("/invoices/" + id + "/cancel", map("version", "0"),
                map("X-CSRF-TOKEN", token));
        HttpResponse<String> invalid = browser.postForm("/invoices", map("invoiceNumber", "FV-2026-0100",
                "creditorIban", "ES00 2100 0418 4502 0005 1332", "debtorName", "X", "amount", "1.00",
                "currency", "EUR", "reference", "", "dueDate", "2026-10-31"), map("X-CSRF-TOKEN", token));

        assertThat(cancelled.statusCode()).isEqualTo(302);
        assertThat(browser.get("/invoices?status=CANCELLED").body()).contains("FV-2026-0099");
        assertThat(invalid.statusCode()).isEqualTo(400);
        assertThat(invalid.body()).contains("IBAN check digits are invalid");
    }

    @Test
    void api_decisions_use_if_match_with_the_payment_version() {
        HttpResponse<String> queue = browser.get("/api/v1/review-queue");
        String proposalId = find(queue.body(), "\"proposalId\":\"([0-9a-f-]{36})\"");
        String version = find(queue.body(), "\"version\":\"\\\\\"(\\d+)\\\\\"\"");
        String confirm = "/api/v1/proposals/" + proposalId + "/confirm";

        HttpResponse<String> missing = browser.postJson(confirm, "");
        HttpResponse<String> stale = browser.postJson(confirm, "", map("If-Match", "\"" + (Long.parseLong(version) + 5) + "\""));
        HttpResponse<String> done = browser.postJson(confirm, "", map("If-Match", "\"" + version + "\""));
        HttpResponse<String> again = browser.postJson(confirm, "", map("If-Match", "\"" + version + "\""));

        assertThat(queue.body()).contains("\"rule\":\"R4\"").contains("\"confidence\":\"0.80\"");
        assertThat(missing.statusCode()).isEqualTo(428);
        assertThat(stale.statusCode()).as("B34").isEqualTo(412);
        assertThat(stale.body()).contains("urn:abgleich:problem:stale-decision");
        assertThat(done.statusCode()).isEqualTo(200);
        assertThat(done.body()).contains("\"outcome\":\"DONE\"");
        assertThat(again.statusCode()).as("B33").isEqualTo(200);
        assertThat(again.body()).contains("\"outcome\":\"ALREADY_DONE\"");
    }

    @Test
    void api_lists_invoices_and_cancels_with_the_etag() {
        HttpResponse<String> detail = browser.get("/api/v1/invoices/" + invoiceId);
        String etag = detail.headers().firstValue("ETag").orElseThrow();

        HttpResponse<String> cancelled = browser.postJson("/api/v1/invoices/" + invoiceId + "/cancel", "", map("If-Match", etag));

        assertThat(detail.body()).contains("\"invoiceNumber\":\"FV-2026-0087\"").contains("\"allocations\":[{");
        assertThat(cancelled.statusCode()).isEqualTo(200);
        assertThat(browser.get("/api/v1/invoices?status=CANCELLED").body()).contains("FV-2026-0087");
        assertThat(browser.get("/api/v1/invoices?status=NOPE").statusCode()).isEqualTo(400);
    }

    @Test
    void B30_new_invoice_rematches_pending() {
        upload(csv("640.00", "Rechnung Pinturas", "CSV-0002").replace("FV-2026-0087", "x"));
        assertThat(jdbc.queryForObject("select status from bank_transaction where amount = 640.00", String.class))
                .isEqualTo("UNMATCHED");

        HttpResponse<String> created = browser.postJson("/api/v1/invoices", """
                {"invoiceNumber": "FV-2026-0120", "creditorIban": "ES9121000418450200051332",
                 "debtorName": "Pinturas Sol SA", "amount": "640.00", "currency": "EUR",
                 "reference": null, "dueDate": "2026-09-30"}
                """);

        assertThat(created.statusCode()).isEqualTo(201);
        assertThat(jdbc.queryForObject("select status from bank_transaction where amount = 640.00", String.class))
                .as("the waiting payment is proposed as soon as its invoice exists").isEqualTo("PROPOSED");
    }

    private ReviewItem pending() {
        return reviewQueue.pending(10).getFirst();
    }

    private static String confirmPath(ReviewItem item) {
        return "/review/proposals/" + item.proposals().getFirst().groupId() + "/confirm";
    }

    private static String rejectPath(ReviewItem item) {
        return "/review/proposals/" + item.proposals().getFirst().groupId() + "/reject";
    }

    private String status(String table) {
        return jdbc.queryForObject("select status from " + table + " limit 1", String.class);
    }

    private void upload(String csv) {
        processStatement.process(command(csv));
    }

    private static ImportStatementCommand command(String csv) {
        return new ImportStatementCommand(ImportSource.REST,
                () -> new ByteArrayInputStream(csv.getBytes(StandardCharsets.UTF_8)));
    }

    /** A one-payment Abgleich CSV for the Spanish account; the closing balance always adds up. */
    private static String csv(String amount, String remittance, String bankReference) {
        return "#abgleich-csv;version=1;account=" + ACCOUNT.value() + ";currency=EUR;opening=0.00;"
                + "opening_date=2026-09-14;closing=" + amount + ";closing_date=2026-09-15\n"
                + "booking_date;value_date;direction;amount;reference;remittance_text;counterparty_name;"
                + "end_to_end_id;bank_reference;charges\n"
                + "2026-09-15;2026-09-15;CREDIT;" + amount + ";;\"" + remittance.replace("\"", "\"\"")
                + "\";PINTURAS SOL SA;;" + bankReference + ";\n";
    }

    private static String find(String text, String regex) {
        Matcher matcher = Pattern.compile(regex).matcher(text);
        assertThat(matcher.find()).as("%s in %s", regex, text).isTrue();
        return matcher.group(1);
    }
}

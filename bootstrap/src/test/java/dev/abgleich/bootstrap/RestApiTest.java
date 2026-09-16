package dev.abgleich.bootstrap;

import static dev.abgleich.bootstrap.Browser.map;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;

/** The JSON API as an integration would use it. */
@AbgleichIntegrationTest
class RestApiTest {

    private static final byte[] NORMA43 = fixture("norma43/spain-two-accounts-2026-09-15.n43");
    private static final String INVOICE_87 = """
            {"invoiceNumber": "FV-2026-0087", "creditorIban": "ES91 2100 0418 4502 0005 1332",
             "debtorName": "Talleres Ruiz SL", "amount": "1815.00", "currency": "EUR",
             "reference": null, "dueDate": "2026-09-30"}
            """;

    @Value("${local.server.port}")
    int port;

    @Autowired
    JdbcTemplate jdbc;

    private Browser api;

    @BeforeEach
    void setUp() {
        jdbc.execute("truncate allocation, bank_transaction, invoice, statement_import");
        api = new Browser(port);
    }

    @Test
    void registers_an_invoice() {
        HttpResponse<String> response = api.postJson("/api/v1/invoices", INVOICE_87);

        assertThat(response.statusCode()).isEqualTo(201);
        assertThat(response.body()).matches("\\{\"id\":\"[0-9a-f-]{36}\"}");
    }

    @Test
    void B24_duplicate_invoice_number_is_a_conflict() {
        api.postJson("/api/v1/invoices", INVOICE_87);

        HttpResponse<String> duplicate = api.postJson("/api/v1/invoices", INVOICE_87.replace("FV-2026-0087", "fv-2026-0087"));

        assertThat(duplicate.statusCode()).isEqualTo(409);
        assertThat(duplicate.headers().firstValue("Content-Type")).hasValueSatisfying(type ->
                assertThat(type).startsWith("application/problem+json"));
        assertThat(duplicate.body()).contains("\"type\":\"urn:abgleich:problem:duplicate-invoice\"");
    }

    @Test
    void invalid_invoices_are_bad_requests_with_a_readable_reason() {
        HttpResponse<String> badIban = api.postJson("/api/v1/invoices", INVOICE_87.replace("ES91", "ES00"));
        HttpResponse<String> missingAmount = api.postJson("/api/v1/invoices", INVOICE_87.replace("\"1815.00\"", "null"));
        HttpResponse<String> floatingAmount = api.postJson("/api/v1/invoices", INVOICE_87.replace("1815.00", "1815.001"));
        HttpResponse<String> malformedJson = api.postJson("/api/v1/invoices", "{\"invoiceNumber\":");

        assertThat(badIban.statusCode()).isEqualTo(400);
        assertThat(badIban.body()).contains("IBAN check digits are invalid");
        assertThat(missingAmount.body()).contains("Field 'amount' is required");
        assertThat(floatingAmount.body()).contains("more than 2 decimals");
        assertThat(malformedJson.statusCode()).isEqualTo(400);
    }

    @Test
    void B21_upload_is_created_once_and_then_reported_as_already_imported() {
        api.postJson("/api/v1/invoices", INVOICE_87.replace("\"reference\": null", "\"reference\": null"));

        HttpResponse<String> first = api.upload("/api/v1/statements", NORMA43, Map.of(), Map.of());
        HttpResponse<String> second = api.upload("/api/v1/statements", NORMA43, Map.of(), Map.of());

        assertThat(first.statusCode()).isEqualTo(201);
        assertThat(first.body())
                .contains("\"outcome\":\"IMPORTED\"")
                .contains("\"format\":\"NORMA43\"")
                .contains("\"account\":\"ES9121000418450200051332\"")
                .contains("\"closingBalance\":{\"amount\":\"8263.50\",\"currency\":\"EUR\",\"date\":\"2026-09-15\"}");
        assertThat(second.statusCode()).isEqualTo(200);
        assertThat(second.body()).contains("\"outcome\":\"ALREADY_IMPORTED\"");
    }

    @Test
    void report_lists_the_stored_transactions() {
        HttpResponse<String> upload = api.upload("/api/v1/statements", NORMA43, Map.of(), Map.of());
        String importId = firstImportId(upload.body());

        HttpResponse<String> report = api.get("/api/v1/statements/" + importId);

        assertThat(report.statusCode()).isEqualTo(200);
        assertThat(report.body())
                .contains("\"remittanceText\":\"TRANSF JOSE MUÑOZ ÁLVAREZ FRA 90\"")
                .contains("\"amount\":{\"amount\":\"3.50\",\"currency\":\"EUR\"}")
                .contains("\"direction\":\"DEBIT\"");
    }

    @Test
    void unknown_report_is_not_found() {
        assertThat(api.get("/api/v1/statements/" + UUID.randomUUID()).statusCode()).isEqualTo(404);
    }

    @Test
    void unsupported_file_is_unprocessable_with_the_reason() {
        HttpResponse<String> response = api.upload("/api/v1/statements",
                "%PDF-1.7 not a statement".getBytes(StandardCharsets.US_ASCII), Map.of(), Map.of());

        assertThat(response.statusCode()).isEqualTo(422);
        assertThat(response.body()).contains("\"reason\":\"UNKNOWN_FORMAT\"");
    }

    @Test
    void B42_oversized_upload_is_rejected_with_a_problem() {
        HttpResponse<String> response = api.upload("/api/v1/statements", new byte[21 * 1024 * 1024],
                Map.of(), map("Accept", "application/problem+json"));

        assertThat(response.statusCode()).isEqualTo(413);
        assertThat(response.body()).contains("urn:abgleich:problem:file-too-large");
    }

    private static String firstImportId(String json) {
        Matcher matcher = Pattern.compile("\"importId\":\"([0-9a-f-]{36})\"").matcher(json);
        assertThat(matcher.find()).as("import id in %s", json).isTrue();
        return matcher.group(1);
    }

    private static byte[] fixture(String name) {
        try (InputStream in = RestApiTest.class.getResourceAsStream("/fixtures/" + name)) {
            return in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}

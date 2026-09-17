package dev.abgleich.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;

import dev.abgleich.bootstrap.web.CorrelationIdFilter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;

/** Through the real HTTP stack: every answer carries the id its log lines were written with. */
@AbgleichIntegrationTest
class CorrelationIdTest {

    private final HttpClient client = HttpClient.newHttpClient();

    @Value("${local.server.port}")
    int port;

    @Test
    void every_page_answers_with_a_correlation_id() {
        Optional<String> id = get("/", null).headers().firstValue(CorrelationIdFilter.HEADER);

        assertThat(id).hasValueSatisfying(value -> assertThat(value).matches("[A-Za-z0-9]{16}"));
    }

    @Test
    void an_id_sent_by_an_integration_is_used_for_this_request_too() {
        HttpResponse<String> response = get("/api/v1/reports/summary", "erp-nightly-0042");

        assertThat(response.headers().firstValue(CorrelationIdFilter.HEADER)).hasValue("erp-nightly-0042");
    }

    @Test
    void two_requests_get_different_ids() {
        String first = get("/", null).headers().firstValue(CorrelationIdFilter.HEADER).orElseThrow();
        String second = get("/", null).headers().firstValue(CorrelationIdFilter.HEADER).orElseThrow();

        assertThat(first).isNotEqualTo(second);
    }

    private HttpResponse<String> get(String path, String correlationId) {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET();
        if (correlationId != null) {
            request.header(CorrelationIdFilter.HEADER, correlationId);
        }
        try {
            return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}

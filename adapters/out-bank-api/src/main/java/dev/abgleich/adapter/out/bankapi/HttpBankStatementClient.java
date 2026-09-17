package dev.abgleich.adapter.out.bankapi;

import dev.abgleich.application.statement.port.out.BankStatementFetchPort;
import dev.abgleich.application.statement.port.out.BankUnavailableException;
import dev.abgleich.domain.account.Iban;
import java.io.InputStream;
import java.net.http.HttpClient;
import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Client of the bank's statement API. Every failure, from a refused connection to an unexpected answer,
 * becomes {@link BankUnavailableException} (B39): the fetch is simply tried again at the next run.
 *
 * <p>The listing is untrusted input. Its ids are validated before they are used in a download URL, and the
 * file itself is streamed into the import, which enforces the size limit (B42).
 */
public final class HttpBankStatementClient implements BankStatementFetchPort {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final RestClient http;

    /**
     * @param baseUrl for example {@code https://api.bank.example}
     * @param token the API token, from configuration outside git (B45)
     */
    public HttpBankStatementClient(String baseUrl, String token, Duration connectTimeout, Duration readTimeout) {
        Objects.requireNonNull(baseUrl, "baseUrl");
        Objects.requireNonNull(token, "token");
        JdkClientHttpRequestFactory requests = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(connectTimeout).build());
        requests.setReadTimeout(readTimeout);
        this.http = RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(requests)
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .build();
    }

    @Override
    public List<RemoteStatement> listSince(Iban account, LocalDate since) {
        String body;
        try {
            body = http.get()
                    .uri("/v1/accounts/{iban}/statements?bookedFrom={since}", account.value(), since)
                    .accept(MediaType.APPLICATION_JSON)
                    .retrieve()
                    .body(String.class);
        } catch (RestClientException e) {
            throw new BankUnavailableException("The bank did not list the statements: " + e.getClass().getSimpleName(), e);
        }
        return parseListing(body);
    }

    private List<RemoteStatement> parseListing(String body) {
        try {
            JsonNode statements = JSON.readTree(Objects.requireNonNullElse(body, "")).path("statements");
            if (!statements.isArray()) {
                throw new BankUnavailableException("The bank answered without a statement list");
            }
            List<RemoteStatement> result = new ArrayList<>();
            for (JsonNode statement : statements) {
                String id = statement.path("id").asString("");
                LocalDate bookingDate = LocalDate.parse(statement.path("bookingDate").asString(""));
                result.add(new RemoteStatement(id, bookingDate, () -> download(id)));
            }
            return List.copyOf(result);
        } catch (JacksonException | DateTimeParseException | IllegalArgumentException e) {
            throw new BankUnavailableException("The bank answered with an invalid statement list", e);
        }
    }

    /** The caller closes the stream, which releases the connection. */
    private InputStream download(String id) {
        try {
            return http.get()
                    .uri("/v1/statements/{id}", id)
                    .accept(MediaType.APPLICATION_OCTET_STREAM, MediaType.APPLICATION_XML, MediaType.TEXT_PLAIN)
                    .exchange((request, response) -> {
                        if (!response.getStatusCode().is2xxSuccessful()) {
                            response.close();
                            throw new BankUnavailableException("The bank refused the download with status "
                                    + response.getStatusCode().value());
                        }
                        return response.getBody();
                    }, false);
        } catch (RestClientException e) {
            throw new BankUnavailableException("The statement could not be downloaded: " + e.getClass().getSimpleName(), e);
        }
    }
}

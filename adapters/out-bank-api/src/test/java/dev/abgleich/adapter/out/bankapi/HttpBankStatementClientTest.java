package dev.abgleich.adapter.out.bankapi;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.abgleich.application.statement.port.out.BankStatementFetchPort.RemoteStatement;
import dev.abgleich.application.statement.port.out.BankUnavailableException;
import dev.abgleich.domain.account.Iban;
import dev.abgleich.mockbank.MockBank;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class HttpBankStatementClientTest {

    private static final Iban ACCOUNT = Iban.of("CH9300762011623852957");
    private static final String TOKEN = "bank-api-test-only";

    private static MockBank bank;

    @BeforeAll
    static void startBank() {
        bank = MockBank.start(0, TOKEN);
    }

    @AfterAll
    static void stopBank() {
        bank.close();
    }

    @BeforeEach
    void clear() {
        bank.clear();
    }

    @Test
    void lists_statements_booked_since_the_date_and_downloads_them_byte_for_byte() throws IOException {
        byte[] content = "<Document>ä statement</Document>".getBytes(StandardCharsets.ISO_8859_1);
        bank.publish(ACCOUNT.value(), LocalDate.of(2026, 9, 10), "too old".getBytes(StandardCharsets.UTF_8));
        String id = bank.publish(ACCOUNT.value(), LocalDate.of(2026, 9, 15), content);
        bank.publish("ES9121000418450200051332", LocalDate.of(2026, 9, 15), "other account".getBytes(StandardCharsets.UTF_8));

        List<RemoteStatement> statements = client(TOKEN).listSince(ACCOUNT, LocalDate.of(2026, 9, 13));

        assertThat(statements).singleElement().satisfies(statement -> {
            assertThat(statement.id()).isEqualTo(id);
            assertThat(statement.bookingDate()).isEqualTo(LocalDate.of(2026, 9, 15));
        });
        try (InputStream in = statements.getFirst().content().open()) {
            assertThat(in.readAllBytes()).isEqualTo(content);
        }
    }

    @Test
    void B39_wrong_token_unavailable_bank_and_unreachable_host_are_all_bank_unavailable() {
        assertThatThrownBy(() -> client("wrong").listSince(ACCOUNT, LocalDate.of(2026, 9, 1)))
                .isInstanceOf(BankUnavailableException.class);

        bank.unavailable(true);
        assertThatThrownBy(() -> client(TOKEN).listSince(ACCOUNT, LocalDate.of(2026, 9, 1)))
                .isInstanceOf(BankUnavailableException.class);

        HttpBankStatementClient nowhere = new HttpBankStatementClient("http://127.0.0.1:1", TOKEN,
                Duration.ofSeconds(2), Duration.ofSeconds(2));
        assertThatThrownBy(() -> nowhere.listSince(ACCOUNT, LocalDate.of(2026, 9, 1)))
                .isInstanceOf(BankUnavailableException.class);
    }

    @Test
    void B39_a_download_that_fails_after_listing_is_bank_unavailable() {
        bank.publish(ACCOUNT.value(), LocalDate.of(2026, 9, 15), "content".getBytes(StandardCharsets.UTF_8));
        RemoteStatement statement = client(TOKEN).listSince(ACCOUNT, LocalDate.of(2026, 9, 1)).getFirst();
        bank.clear();

        assertThatThrownBy(() -> statement.content().open()).isInstanceOf(BankUnavailableException.class)
                .hasMessageContaining("404");
    }

    private static HttpBankStatementClient client(String token) {
        return new HttpBankStatementClient(bank.baseUrl(), token, Duration.ofSeconds(2), Duration.ofSeconds(5));
    }
}

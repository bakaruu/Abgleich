package dev.abgleich.application.service;

import static org.assertj.core.api.Assertions.assertThat;

import dev.abgleich.application.port.in.FetchStatementsUseCase.AccountFetch;
import dev.abgleich.application.port.in.FetchStatementsUseCase.FetchRun;
import dev.abgleich.application.port.in.ImportResult;
import dev.abgleich.application.port.in.ImportSource;
import dev.abgleich.application.port.in.ProcessStatementUseCase;
import dev.abgleich.application.port.in.StatementProcessed;
import dev.abgleich.application.port.out.BankStatementFetchPort;
import dev.abgleich.application.port.out.BankUnavailableException;
import dev.abgleich.application.port.out.StatementFormat;
import dev.abgleich.domain.account.Iban;
import dev.abgleich.domain.statement.InvalidStatementException;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class FetchStatementsServiceTest {

    private static final Iban SWISS = Iban.of("CH9300762011623852957");
    private static final Iban SPANISH = Iban.of("ES9121000418450200051332");
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-16T05:00:00Z"), ZoneOffset.UTC);

    private final FakeBank bank = new FakeBank();
    private final List<String> processed = new ArrayList<>();
    private final List<ImportSource> sources = new ArrayList<>();

    private final ProcessStatementUseCase process = command -> {
        sources.add(command.source());
        String content;
        try (var in = command.content().open()) {
            content = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        processed.add(content);
        if (content.startsWith("broken")) {
            throw new InvalidStatementException(InvalidStatementException.Reason.UNBALANCED, "does not add up");
        }
        ImportResult.Outcome outcome = content.startsWith("old")
                ? ImportResult.Outcome.ALREADY_IMPORTED
                : ImportResult.Outcome.IMPORTED;
        return new StatementProcessed(new ImportResult(outcome, StatementFormat.CAMT053_V04, List.of()), List.of());
    };

    @Test
    void fetches_a_window_of_recent_days_and_processes_every_file_as_a_bank_api_import() {
        bank.files.put(SWISS, List.of("new-1", "old-2", "broken-3"));
        FetchStatementsService service = new FetchStatementsService(List.of(SWISS), 3, bank, process, CLOCK);

        FetchRun run = service.fetchLatest();

        assertThat(bank.requestedSince).containsExactly(LocalDate.of(2026, 9, 13));
        assertThat(run.accounts()).containsExactly(new AccountFetch(SWISS, 1, 1, 1, false));
        assertThat(processed).containsExactly("new-1", "old-2", "broken-3");
        assertThat(sources).containsOnly(ImportSource.BANK_API);
    }

    @Test
    void an_unavailable_bank_for_one_account_does_not_stop_the_others() {
        bank.unavailable = SWISS;
        bank.files.put(SPANISH, List.of("new-1"));
        FetchStatementsService service = new FetchStatementsService(List.of(SWISS, SPANISH), 3, bank, process, CLOCK);

        FetchRun run = service.fetchLatest();

        assertThat(run.accounts()).containsExactly(
                new AccountFetch(SWISS, 0, 0, 0, true),
                new AccountFetch(SPANISH, 1, 0, 0, false));
    }

    private static final class FakeBank implements BankStatementFetchPort {
        private final Map<Iban, List<String>> files = new HashMap<>();
        private final List<LocalDate> requestedSince = new ArrayList<>();
        private Iban unavailable;

        @Override
        public List<RemoteStatement> listSince(Iban account, LocalDate since) {
            requestedSince.add(since);
            if (account.equals(unavailable)) {
                throw new BankUnavailableException("timeout");
            }
            return files.getOrDefault(account, List.of()).stream()
                    .map(content -> new RemoteStatement(content, since, () -> new ByteArrayInputStream(
                            content.getBytes(StandardCharsets.UTF_8))))
                    .toList();
        }
    }
}

package dev.abgleich.application.service;

import dev.abgleich.application.port.in.FetchStatementsUseCase;
import dev.abgleich.application.port.in.ImportResult;
import dev.abgleich.application.port.in.ImportSource;
import dev.abgleich.application.port.in.ImportStatementCommand;
import dev.abgleich.application.port.in.ProcessStatementUseCase;
import dev.abgleich.application.port.out.BankStatementFetchPort;
import dev.abgleich.application.port.out.BankStatementFetchPort.RemoteStatement;
import dev.abgleich.application.port.out.BankUnavailableException;
import dev.abgleich.domain.account.Iban;
import dev.abgleich.domain.statement.InvalidStatementException;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

/**
 * Fetches a sliding window of recent days instead of remembering the last file it downloaded. Importing is
 * idempotent (B21), so a file fetched again changes nothing, and a statement the bank publishes late for an
 * earlier day is still picked up, which a "last fetched" cursor would skip.
 */
public final class FetchStatementsService implements FetchStatementsUseCase {

    private final List<Iban> accounts;
    private final int lookbackDays;
    private final BankStatementFetchPort bank;
    private final ProcessStatementUseCase processStatement;
    private final Clock clock;

    public FetchStatementsService(List<Iban> accounts, int lookbackDays, BankStatementFetchPort bank,
            ProcessStatementUseCase processStatement, Clock clock) {
        this.accounts = List.copyOf(accounts);
        if (lookbackDays < 0) {
            throw new IllegalArgumentException("lookbackDays cannot be negative");
        }
        this.lookbackDays = lookbackDays;
        this.bank = Objects.requireNonNull(bank, "bank");
        this.processStatement = Objects.requireNonNull(processStatement, "processStatement");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public FetchRun fetchLatest() {
        LocalDate since = LocalDate.now(clock).minusDays(lookbackDays);
        return new FetchRun(accounts.stream().map(account -> fetch(account, since)).toList());
    }

    private AccountFetch fetch(Iban account, LocalDate since) {
        int imported = 0;
        int alreadyImported = 0;
        int rejected = 0;
        try {
            for (RemoteStatement statement : bank.listSince(account, since)) {
                try {
                    ImportResult result = processStatement.process(
                            new ImportStatementCommand(ImportSource.BANK_API, statement.content())).imported();
                    if (result.outcome() == ImportResult.Outcome.ALREADY_IMPORTED) {
                        alreadyImported++;
                    } else {
                        imported++;
                    }
                } catch (InvalidStatementException refused) {
                    // One bad file must not block the others; the import stored nothing for it (B11).
                    rejected++;
                }
            }
        } catch (BankUnavailableException unavailable) {
            return new AccountFetch(account, imported, alreadyImported, rejected, true);
        }
        return new AccountFetch(account, imported, alreadyImported, rejected, false);
    }
}

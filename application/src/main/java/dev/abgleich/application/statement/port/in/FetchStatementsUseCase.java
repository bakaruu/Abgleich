package dev.abgleich.application.statement.port.in;

import dev.abgleich.domain.account.Iban;
import java.util.List;
import java.util.Objects;

/**
 * Downloads recent statements of every configured account from the bank API and processes each file like
 * an upload. Runs on a schedule; only one instance runs it at a time (B26).
 */
public interface FetchStatementsUseCase {

    FetchRun fetchLatest();

    record FetchRun(List<AccountFetch> accounts) {
        public FetchRun {
            accounts = List.copyOf(accounts);
        }
    }

    /**
     * @param imported files stored now
     * @param alreadyImported files imported before; downloading them again changed nothing (B21)
     * @param rejected files the import refused, for example because the balances do not add up (B11)
     * @param bankUnavailable the bank could not be reached; the next run tries again
     */
    record AccountFetch(Iban account, int imported, int alreadyImported, int rejected, boolean bankUnavailable) {
        public AccountFetch {
            Objects.requireNonNull(account, "account");
        }
    }
}

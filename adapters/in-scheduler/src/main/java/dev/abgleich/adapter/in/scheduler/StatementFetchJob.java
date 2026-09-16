package dev.abgleich.adapter.in.scheduler;

import dev.abgleich.application.port.in.FetchStatementsUseCase;
import dev.abgleich.application.port.in.FetchStatementsUseCase.AccountFetch;
import java.util.Objects;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Downloads new statements from the bank API on a fixed delay. With several instances running, ShedLock lets
 * one of them run the download and the others skip it, so the bank is not asked twice (B26).
 *
 * <p>Not final: ShedLock wraps the scheduled method in a proxy.
 */
public class StatementFetchJob {

    static final String LOCK = "fetch-bank-statements";
    private static final Logger log = LoggerFactory.getLogger(StatementFetchJob.class);

    private final FetchStatementsUseCase fetchStatements;

    public StatementFetchJob(FetchStatementsUseCase fetchStatements) {
        this.fetchStatements = Objects.requireNonNull(fetchStatements, "fetchStatements");
    }

    @Scheduled(fixedDelayString = "${abgleich.bank-api.fetch-interval}",
            initialDelayString = "${abgleich.bank-api.fetch-interval}")
    @SchedulerLock(name = LOCK, lockAtMostFor = "${abgleich.bank-api.lock-at-most-for:PT30M}",
            lockAtLeastFor = "${abgleich.bank-api.lock-at-least-for:PT30S}")
    public void run() {
        for (AccountFetch account : fetchStatements.fetchLatest().accounts()) {
            if (account.bankUnavailable()) {
                log.warn("Bank API unavailable for account {}; trying again at the next run", account.account());
            } else if (account.imported() + account.rejected() > 0) {
                log.info("Bank API account {}: {} imported, {} already imported, {} rejected", account.account(),
                        account.imported(), account.alreadyImported(), account.rejected());
            }
        }
    }
}

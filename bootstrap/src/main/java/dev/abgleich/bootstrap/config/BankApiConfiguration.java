package dev.abgleich.bootstrap.config;

import dev.abgleich.adapter.in.scheduler.StatementFetchJob;
import dev.abgleich.adapter.out.bankapi.HttpBankStatementClient;
import dev.abgleich.application.statement.port.in.FetchStatementsUseCase;
import dev.abgleich.application.statement.port.in.ProcessStatementUseCase;
import dev.abgleich.application.statement.port.out.BankStatementFetchPort;
import dev.abgleich.application.statement.service.FetchStatementsService;
import dev.abgleich.domain.account.Iban;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** The scheduled download from the bank's statement API, off unless {@code abgleich.bank-api.enabled=true}. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "abgleich.bank-api.enabled", havingValue = "true")
class BankApiConfiguration {

    @Bean
    HttpBankStatementClient bankStatementClient(
            @Value("${abgleich.bank-api.base-url}") String baseUrl,
            @Value("${abgleich.bank-api.token}") String token,
            @Value("${abgleich.bank-api.connect-timeout}") Duration connectTimeout,
            @Value("${abgleich.bank-api.read-timeout}") Duration readTimeout) {
        return new HttpBankStatementClient(baseUrl, token, connectTimeout, readTimeout);
    }

    @Bean
    FetchStatementsService fetchStatementsService(
            @Value("${abgleich.bank-api.accounts}") List<String> accounts,
            @Value("${abgleich.bank-api.lookback-days}") int lookbackDays,
            BankStatementFetchPort bank, ProcessStatementUseCase processStatement, Clock clock) {
        return new FetchStatementsService(accounts.stream().map(Iban::of).toList(), lookbackDays, bank,
                processStatement, clock);
    }

    @Bean
    StatementFetchJob statementFetchJob(FetchStatementsUseCase fetchStatements) {
        return new StatementFetchJob(fetchStatements);
    }
}

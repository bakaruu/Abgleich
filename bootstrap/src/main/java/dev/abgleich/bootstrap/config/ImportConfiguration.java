package dev.abgleich.bootstrap.config;

import dev.abgleich.adapter.out.camt.Camt053Parser;
import dev.abgleich.adapter.out.camt.Camt054Parser;
import dev.abgleich.adapter.out.csv.CsvStatementParser;
import dev.abgleich.adapter.out.norma43.Norma43Parser;
import dev.abgleich.adapter.out.postgres.JdbcStatementImportRepository;
import dev.abgleich.application.port.in.ImportStatementUseCase;
import dev.abgleich.application.port.out.StatementImportRepositoryPort;
import dev.abgleich.application.port.out.StatementParserPort;
import dev.abgleich.application.service.ImportStatementService;
import java.time.Clock;
import java.util.List;
import javax.sql.DataSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Wires the statement import: parsers and repository behind their ports, and the use case. */
@Configuration(proxyBeanMethods = false)
class ImportConfiguration {

    @Bean
    Camt053Parser camt053Parser() {
        return new Camt053Parser();
    }

    @Bean
    Camt054Parser camt054Parser() {
        return new Camt054Parser();
    }

    @Bean
    Norma43Parser norma43Parser() {
        return new Norma43Parser();
    }

    @Bean
    CsvStatementParser csvStatementParser() {
        return new CsvStatementParser();
    }

    @Bean
    JdbcStatementImportRepository statementImportRepository(DataSource dataSource,
            PlatformTransactionManager transactionManager) {
        return new JdbcStatementImportRepository(dataSource, new TransactionTemplate(transactionManager));
    }

    @Bean
    ImportStatementUseCase importStatementUseCase(List<StatementParserPort> parsers,
            StatementImportRepositoryPort repository, Clock clock) {
        return new ImportStatementService(parsers, repository, clock);
    }
}

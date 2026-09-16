package dev.abgleich.bootstrap.config;

import dev.abgleich.adapter.out.postgres.JdbcStatementReportRepository;
import dev.abgleich.application.port.in.ImportStatementUseCase;
import dev.abgleich.application.port.in.ProcessStatementUseCase;
import dev.abgleich.application.port.in.ReconcileUseCase;
import dev.abgleich.application.port.in.StatementReportQuery;
import dev.abgleich.application.port.out.StatementReportRepositoryPort;
import dev.abgleich.application.service.ProcessStatementService;
import dev.abgleich.application.service.StatementReportService;
import javax.sql.DataSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Wires what the web UI and the API call: upload processing and the statement report. */
@Configuration(proxyBeanMethods = false)
class StatementProcessingConfiguration {

    @Bean
    ProcessStatementUseCase processStatementUseCase(ImportStatementUseCase importStatement, ReconcileUseCase reconcile) {
        return new ProcessStatementService(importStatement, reconcile);
    }

    @Bean
    JdbcStatementReportRepository statementReportRepository(DataSource dataSource) {
        return new JdbcStatementReportRepository(dataSource);
    }

    @Bean
    StatementReportQuery statementReportQuery(StatementReportRepositoryPort reports) {
        return new StatementReportService(reports);
    }
}

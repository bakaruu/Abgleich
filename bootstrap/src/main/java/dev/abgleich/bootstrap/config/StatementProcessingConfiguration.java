package dev.abgleich.bootstrap.config;

import dev.abgleich.adapter.out.postgres.JdbcStatementReportRepository;
import dev.abgleich.adapter.out.postgres.JdbcSummaryRepository;
import dev.abgleich.application.reconciliation.port.in.ReconcileUseCase;
import dev.abgleich.application.reporting.port.in.SummaryQuery;
import dev.abgleich.application.reporting.port.out.SummaryRepositoryPort;
import dev.abgleich.application.reporting.service.SummaryService;
import dev.abgleich.application.statement.port.in.ImportStatementUseCase;
import dev.abgleich.application.statement.port.in.ProcessStatementUseCase;
import dev.abgleich.application.statement.port.in.StatementReportQuery;
import dev.abgleich.application.statement.port.out.StatementReportRepositoryPort;
import dev.abgleich.application.statement.service.ProcessStatementService;
import dev.abgleich.application.statement.service.StatementReportService;
import dev.abgleich.bootstrap.metrics.MeteredProcessStatement;
import io.micrometer.core.instrument.MeterRegistry;
import javax.sql.DataSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Wires what the web UI and the API call: upload processing, the statement report and the summary. */
@Configuration(proxyBeanMethods = false)
class StatementProcessingConfiguration {

    /** Every channel (web, REST, SFTP, bank API, example) goes through this one bean, so all are counted. */
    @Bean
    ProcessStatementUseCase processStatementUseCase(ImportStatementUseCase importStatement, ReconcileUseCase reconcile,
            MeterRegistry registry) {
        return new MeteredProcessStatement(new ProcessStatementService(importStatement, reconcile), registry);
    }

    @Bean
    JdbcStatementReportRepository statementReportRepository(DataSource dataSource) {
        return new JdbcStatementReportRepository(dataSource);
    }

    @Bean
    StatementReportQuery statementReportQuery(StatementReportRepositoryPort reports) {
        return new StatementReportService(reports);
    }

    @Bean
    JdbcSummaryRepository summaryRepository(DataSource dataSource, PlatformTransactionManager transactionManager) {
        return new JdbcSummaryRepository(dataSource, new TransactionTemplate(transactionManager));
    }

    @Bean
    SummaryQuery summaryQuery(SummaryRepositoryPort summaries) {
        return new SummaryService(summaries);
    }
}

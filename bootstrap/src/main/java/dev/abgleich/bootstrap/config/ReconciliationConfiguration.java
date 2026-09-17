package dev.abgleich.bootstrap.config;

import dev.abgleich.adapter.out.postgres.JdbcInvoiceRepository;
import dev.abgleich.adapter.out.postgres.JdbcReconciliationQueries;
import dev.abgleich.adapter.out.postgres.JdbcReconciliationRepository;
import dev.abgleich.adapter.out.postgres.JdbcReviewRepository;
import dev.abgleich.application.invoice.port.out.InvoiceRepositoryPort;
import dev.abgleich.application.invoice.service.RegisterInvoiceService;
import dev.abgleich.application.reconciliation.port.in.ReconcileUseCase;
import dev.abgleich.application.reconciliation.port.out.ReconciliationQueriesPort;
import dev.abgleich.application.reconciliation.port.out.ReconciliationRepositoryPort;
import dev.abgleich.application.reconciliation.port.out.ReviewRepositoryPort;
import dev.abgleich.application.reconciliation.service.ReconcileService;
import dev.abgleich.application.reconciliation.service.ReconciliationQueriesService;
import dev.abgleich.application.reconciliation.service.ReviewService;
import dev.abgleich.domain.matching.Matcher;
import dev.abgleich.domain.matching.MatchingPolicy;
import java.time.Clock;
import javax.sql.DataSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Wires invoices, matching and review: repositories behind their ports, the domain matcher and the use cases. */
@Configuration(proxyBeanMethods = false)
class ReconciliationConfiguration {

    @Bean
    JdbcInvoiceRepository invoiceRepository(DataSource dataSource) {
        return new JdbcInvoiceRepository(dataSource);
    }

    @Bean
    JdbcReconciliationRepository reconciliationRepository(DataSource dataSource,
            PlatformTransactionManager transactionManager) {
        return new JdbcReconciliationRepository(dataSource, new TransactionTemplate(transactionManager));
    }

    @Bean
    JdbcReviewRepository reviewRepository(DataSource dataSource, PlatformTransactionManager transactionManager) {
        return new JdbcReviewRepository(dataSource, new TransactionTemplate(transactionManager));
    }

    @Bean
    JdbcReconciliationQueries reconciliationQueries(DataSource dataSource) {
        return new JdbcReconciliationQueries(dataSource);
    }

    @Bean
    Matcher matcher() {
        return new Matcher(MatchingPolicy.defaults());
    }

    @Bean
    ReconcileService reconcileService(ReconciliationRepositoryPort reconciliations, InvoiceRepositoryPort invoices,
            Matcher matcher, Clock clock) {
        return new ReconcileService(reconciliations, invoices, matcher, clock);
    }

    /** Registering and cancelling invoices; registration reconciles pending payments of the account (B30). */
    @Bean
    RegisterInvoiceService invoiceService(InvoiceRepositoryPort invoices, ReconcileUseCase reconcile) {
        return new RegisterInvoiceService(invoices, reconcile);
    }

    @Bean
    ReviewService reviewService(ReviewRepositoryPort reviews, Clock clock) {
        return new ReviewService(reviews, clock);
    }

    @Bean
    ReconciliationQueriesService reconciliationQueriesService(ReconciliationQueriesPort queries) {
        return new ReconciliationQueriesService(queries);
    }
}

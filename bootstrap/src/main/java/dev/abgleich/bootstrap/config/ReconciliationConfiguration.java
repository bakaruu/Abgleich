package dev.abgleich.bootstrap.config;

import dev.abgleich.adapter.out.postgres.JdbcInvoiceRepository;
import dev.abgleich.adapter.out.postgres.JdbcReconciliationRepository;
import dev.abgleich.application.port.in.ReconcileUseCase;
import dev.abgleich.application.port.in.RegisterInvoiceUseCase;
import dev.abgleich.application.port.out.InvoiceRepositoryPort;
import dev.abgleich.application.port.out.ReconciliationRepositoryPort;
import dev.abgleich.application.service.ReconcileService;
import dev.abgleich.application.service.RegisterInvoiceService;
import dev.abgleich.domain.matching.Matcher;
import java.time.Clock;
import javax.sql.DataSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Wires invoices and matching: repositories behind their ports, the domain matcher and the use cases. */
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
    Matcher matcher() {
        return new Matcher();
    }

    @Bean
    RegisterInvoiceUseCase registerInvoiceUseCase(InvoiceRepositoryPort invoices) {
        return new RegisterInvoiceService(invoices);
    }

    @Bean
    ReconcileUseCase reconcileUseCase(ReconciliationRepositoryPort reconciliations, InvoiceRepositoryPort invoices,
            Matcher matcher, Clock clock) {
        return new ReconcileService(reconciliations, invoices, matcher, clock);
    }
}

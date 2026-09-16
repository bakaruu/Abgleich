package dev.abgleich.bootstrap.config;

import dev.abgleich.adapter.in.kafka.InvoiceCreatedListener;
import dev.abgleich.adapter.in.kafka.RejectedMessageException;
import dev.abgleich.adapter.out.postgres.JdbcInboxRepository;
import dev.abgleich.application.port.in.ReceiveInvoiceUseCase;
import dev.abgleich.application.port.in.ReconcileUseCase;
import dev.abgleich.application.port.out.InboxRepositoryPort;
import dev.abgleich.application.service.ReceiveInvoiceService;
import java.time.Clock;
import java.time.Duration;
import javax.sql.DataSource;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.TopicPartition;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.backoff.ExponentialBackOff;

/** Wires invoices that arrive as messages: Kafka events and REST requests with an idempotency key (B24). */
@Configuration(proxyBeanMethods = false)
class InvoiceIntakeConfiguration {

    @Bean
    JdbcInboxRepository inboxRepository(DataSource dataSource, PlatformTransactionManager transactionManager) {
        return new JdbcInboxRepository(dataSource, new TransactionTemplate(transactionManager));
    }

    @Bean
    ReceiveInvoiceService receiveInvoiceService(InboxRepositoryPort inbox, ReconcileUseCase reconcile, Clock clock) {
        return new ReceiveInvoiceService(inbox, reconcile, clock);
    }

    @Bean
    InvoiceCreatedListener invoiceCreatedListener(ReceiveInvoiceUseCase receiveInvoice) {
        return new InvoiceCreatedListener(receiveInvoice);
    }

    /**
     * A message that can never be processed goes to the dead letter topic at once. Anything else, such as the
     * database being down, is retried with growing pauses for up to ten minutes before it is parked there too,
     * so an outage does not throw invoices away and a bug does not block the partition forever.
     */
    @Bean
    DefaultErrorHandler kafkaErrorHandler(KafkaTemplate<String, String> kafka,
            @Value("${abgleich.kafka.topics.invoice-created-dead-letter}") String deadLetterTopic) {
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(kafka,
                (record, failure) -> new TopicPartition(deadLetterTopic, -1));
        ExponentialBackOff backOff = new ExponentialBackOff(Duration.ofSeconds(1).toMillis(), 2.0);
        backOff.setMaxInterval(Duration.ofSeconds(30).toMillis());
        backOff.setMaxElapsedTime(Duration.ofMinutes(10).toMillis());
        DefaultErrorHandler handler = new DefaultErrorHandler(recoverer, backOff);
        handler.addNotRetryableExceptions(RejectedMessageException.class);
        return handler;
    }

    @Bean
    NewTopic invoiceCreatedTopic(@Value("${abgleich.kafka.topics.invoice-created}") String topic,
            @Value("${abgleich.kafka.partitions}") int partitions, @Value("${abgleich.kafka.replicas}") short replicas) {
        return TopicBuilder.name(topic).partitions(partitions).replicas(replicas).build();
    }

    @Bean
    NewTopic invoiceCreatedDeadLetterTopic(@Value("${abgleich.kafka.topics.invoice-created-dead-letter}") String topic,
            @Value("${abgleich.kafka.replicas}") short replicas) {
        return TopicBuilder.name(topic).partitions(1).replicas(replicas).build();
    }
}

package dev.abgleich.bootstrap.config;

import dev.abgleich.adapter.in.scheduler.OutboxRelayJob;
import dev.abgleich.adapter.in.scheduler.OutboxRetentionJob;
import dev.abgleich.adapter.out.kafka.KafkaInvoiceEventPublisher;
import dev.abgleich.adapter.out.postgres.JdbcOutboxRepository;
import dev.abgleich.application.port.in.PublishEventsUseCase;
import dev.abgleich.application.port.in.PurgePublishedEventsUseCase;
import dev.abgleich.application.port.out.EventPublisherPort;
import dev.abgleich.application.port.out.OutboxRepositoryPort;
import dev.abgleich.application.service.OutboxRetentionService;
import dev.abgleich.application.service.PublishEventsService;
import java.time.Clock;
import java.time.Duration;
import javax.sql.DataSource;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaTemplate;

/** Wires the outbox, its relay to Kafka (B23) and the retention of published events. */
@Configuration(proxyBeanMethods = false)
class EventsConfiguration {

    @Bean
    JdbcOutboxRepository outboxRepository(DataSource dataSource) {
        return new JdbcOutboxRepository(dataSource);
    }

    @Bean
    KafkaInvoiceEventPublisher invoiceEventPublisher(KafkaTemplate<String, String> kafka,
            @Value("${abgleich.kafka.topics.invoice-events}") String topic,
            @Value("${abgleich.outbox.acknowledgement-timeout}") Duration acknowledgementTimeout) {
        return new KafkaInvoiceEventPublisher(kafka, topic, acknowledgementTimeout);
    }

    @Bean
    PublishEventsService publishEventsService(OutboxRepositoryPort outbox, EventPublisherPort publisher, Clock clock) {
        return new PublishEventsService(outbox, publisher, clock);
    }

    @Bean
    OutboxRelayJob outboxRelayJob(PublishEventsUseCase publishEvents) {
        return new OutboxRelayJob(publishEvents);
    }

    @Bean
    OutboxRetentionService outboxRetentionService(OutboxRepositoryPort outbox,
            @Value("${abgleich.outbox.retention}") Duration retention, Clock clock) {
        return new OutboxRetentionService(outbox, retention, clock);
    }

    @Bean
    OutboxRetentionJob outboxRetentionJob(PurgePublishedEventsUseCase purgePublishedEvents) {
        return new OutboxRetentionJob(purgePublishedEvents);
    }

    /** Keyed by invoice id: the partitions keep the order of the events of each invoice. */
    @Bean
    NewTopic invoiceEventsTopic(@Value("${abgleich.kafka.topics.invoice-events}") String topic,
            @Value("${abgleich.kafka.partitions}") int partitions, @Value("${abgleich.kafka.replicas}") short replicas) {
        return TopicBuilder.name(topic).partitions(partitions).replicas(replicas).build();
    }
}

package dev.abgleich.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;

import dev.abgleich.adapter.out.kafka.KafkaInvoiceEventPublisher;
import dev.abgleich.adapter.out.postgres.JdbcOutboxRepository;
import dev.abgleich.application.events.port.in.PublishEventsUseCase;
import dev.abgleich.application.events.port.in.PublishEventsUseCase.PublishRun;
import dev.abgleich.application.events.service.PublishEventsService;
import dev.abgleich.application.example.port.in.ExampleDataUseCase;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import javax.sql.DataSource;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.kafka.autoconfigure.KafkaConnectionDetails;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;

/**
 * B23 end to end with PostgreSQL and a real Kafka broker. The done criterion of phase F3: an {@code InvoicePaid}
 * event survives a restart of the application in the middle of the process.
 */
@AbgleichIntegrationTest
class OutboxKafkaIntegrationTest {

    @Autowired
    ExampleDataUseCase examples;

    @Autowired
    PublishEventsUseCase publishEvents;

    @Autowired
    DataSource dataSource;

    @Autowired
    JdbcTemplate jdbc;

    /** The broker of the test container; the property in application.yml still names the local default. */
    @Autowired
    KafkaConnectionDetails kafkaConnection;

    @Value("${abgleich.kafka.topics.invoice-events}")
    String topic;

    @BeforeEach
    void emptyDatabase() {
        jdbc.execute("truncate outbox_event, processed_message, allocation, bank_transaction, invoice, statement_import");
    }

    @Test
    void B23_event_survives_crash_after_commit() {
        examples.load();
        List<String> settled = jdbc.queryForList(
                "select invoice_number from invoice where status in ('PAID', 'OVERPAID') order by invoice_number",
                String.class);
        assertThat(settled).as("the example settles invoices").isNotEmpty();
        assertThat(jdbc.queryForList("""
                        select invoice_number from outbox_event
                         where event_type = 'INVOICE_PAID' and published_at is null order by invoice_number
                        """, String.class))
                .as("committed together with the payments, not published yet").isEqualTo(settled);

        // The application stops here. Nothing in memory survives; a new instance only has the database.
        PublishRun afterRestart;
        try (ProducerAfterRestart producer = new ProducerAfterRestart(bootstrapServers())) {
            afterRestart = new PublishEventsService(new JdbcOutboxRepository(dataSource),
                    new KafkaInvoiceEventPublisher(producer.template, topic, Duration.ofSeconds(15)), Clock.systemUTC())
                    .publishPending();
        }

        assertThat(afterRestart).isEqualTo(new PublishRun(settled.size(), 0, 0));
        Map<String, String> published = consume(topic, eventIds());
        assertThat(published.values()).allSatisfy(json -> assertThat(json).contains("\"type\":\"InvoicePaid\""));
        assertThat(published.values().stream().map(OutboxKafkaIntegrationTest::invoiceNumber).sorted().toList())
                .isEqualTo(settled);
        assertThat(publishEvents.publishPending()).as("published once, not again").isEqualTo(new PublishRun(0, 0, 0));
    }

    @Test
    void B23_broker_down_keeps_events_in_the_outbox_until_it_is_back() {
        examples.load();
        long pending = jdbc.queryForObject("select count(*) from outbox_event", Long.class);

        PublishRun whileDown;
        try (ProducerAfterRestart unreachable = new ProducerAfterRestart("127.0.0.1:1")) {
            whileDown = new PublishEventsService(new JdbcOutboxRepository(dataSource),
                    new KafkaInvoiceEventPublisher(unreachable.template, topic, Duration.ofSeconds(3)), Clock.systemUTC())
                    .publishPending();
        }

        assertThat(whileDown).isEqualTo(new PublishRun(0, 1, pending));
        assertThat(jdbc.queryForObject("select max(attempts) from outbox_event", Integer.class)).isEqualTo(1);

        assertThat(publishEvents.publishPending()).isEqualTo(new PublishRun((int) pending, 0, 0));
        assertThat(consume(topic, eventIds())).hasSize((int) pending);
    }

    private String bootstrapServers() {
        return String.join(",", kafkaConnection.getBootstrapServers());
    }

    private Set<String> eventIds() {
        return Set.copyOf(jdbc.queryForList("select id::text from outbox_event", String.class));
    }

    /** Records of the topic with the given event ids, keyed by event id. */
    private Map<String, String> consume(String topic, Set<String> eventIds) {
        Map<String, Object> config = Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers(),
                ConsumerConfig.GROUP_ID_CONFIG, "test-" + UUID.randomUUID(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        Map<String, String> found = new HashMap<>();
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(config)) {
            consumer.subscribe(List.of(topic));
            Instant deadline = Instant.now().plusSeconds(30);
            while (found.size() < eventIds.size() && Instant.now().isBefore(deadline)) {
                for (ConsumerRecord<String, String> record : consumer.poll(Duration.ofMillis(500))) {
                    String eventId = new String(record.headers().lastHeader("abgleich-event-id").value(),
                            StandardCharsets.UTF_8);
                    if (eventIds.contains(eventId)) {
                        assertThat(found.put(eventId, record.value())).as("each event once").isNull();
                    }
                }
            }
        }
        return found;
    }

    private static String invoiceNumber(String json) {
        java.util.regex.Matcher number = java.util.regex.Pattern.compile("\"invoiceNumber\":\"([^\"]+)\"").matcher(json);
        assertThat(number.find()).isTrue();
        return number.group(1);
    }

    /** A producer of its own, as a freshly started application would create. */
    private static final class ProducerAfterRestart implements AutoCloseable {
        private final DefaultKafkaProducerFactory<String, String> factory;
        private final KafkaTemplate<String, String> template;

        ProducerAfterRestart(String bootstrapServers) {
            factory = new DefaultKafkaProducerFactory<>(Map.of(
                    ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers,
                    ProducerConfig.ACKS_CONFIG, "all",
                    ProducerConfig.MAX_BLOCK_MS_CONFIG, 2000,
                    ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, 2000,
                    ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, 2500,
                    ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
                    ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class));
            template = new KafkaTemplate<>(factory);
        }

        @Override
        public void close() {
            factory.destroy();
        }
    }
}

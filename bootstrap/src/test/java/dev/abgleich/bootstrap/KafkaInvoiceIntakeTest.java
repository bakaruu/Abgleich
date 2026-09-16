package dev.abgleich.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.kafka.autoconfigure.KafkaConnectionDetails;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;

/** Invoices from the ERP through a real Kafka broker into PostgreSQL. */
@AbgleichIntegrationTest
class KafkaInvoiceIntakeTest {

    private static final String KEY = "erp-company-1";

    @Autowired
    KafkaTemplate<String, String> kafka;

    @Autowired
    KafkaConnectionDetails kafkaConnection;

    @Autowired
    JdbcTemplate jdbc;

    @Value("${abgleich.kafka.topics.invoice-created}")
    String topic;

    @Value("${abgleich.kafka.topics.invoice-created-dead-letter}")
    String deadLetterTopic;

    @BeforeEach
    void emptyDatabase() {
        jdbc.execute("truncate outbox_event, processed_message, allocation, bank_transaction, invoice, statement_import");
    }

    @Test
    void B24_redelivered_event_is_noop() throws Exception {
        String run = UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT);
        String first = event("evt-" + run + "-1", "F-" + run + "-1");
        String poison = "{\"specVersion\": 1, \"eventId\": \"evt-" + run + "-poison\", \"amount\": 12.5}";
        String second = event("evt-" + run + "-2", "F-" + run + "-2");

        // Same key, so all records share a partition and are consumed in this order.
        send(first);
        send(first);
        send(poison);
        send(second);

        awaitTrue(() -> count("select count(*) from invoice where invoice_number = ?", "F-" + run + "-2") == 1);
        assertThat(count("select count(*) from invoice where invoice_number = ?", "F-" + run + "-1"))
                .as("the redelivered event stored nothing").isEqualTo(1);
        assertThat(count("select count(*) from processed_message where message_id = ?", "evt-" + run + "-1"))
                .isEqualTo(1);
        assertThat(deadLetters()).as("the poison message was parked, not retried forever")
                .anySatisfy(value -> assertThat(value).isEqualTo(poison));
    }

    private void send(String value) throws Exception {
        kafka.send(topic, KEY, value).get();
    }

    private int count(String sql, String parameter) {
        return jdbc.queryForObject(sql, Integer.class, parameter);
    }

    private List<String> deadLetters() {
        Map<String, Object> config = Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, String.join(",", kafkaConnection.getBootstrapServers()),
                ConsumerConfig.GROUP_ID_CONFIG, "test-" + UUID.randomUUID(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        List<String> values = new ArrayList<>();
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(config)) {
            consumer.subscribe(List.of(deadLetterTopic));
            Instant deadline = Instant.now().plusSeconds(20);
            while (values.isEmpty() && Instant.now().isBefore(deadline)) {
                for (ConsumerRecord<String, String> record : consumer.poll(Duration.ofMillis(500))) {
                    values.add(record.value());
                }
            }
        }
        return values;
    }

    private static String event(String eventId, String invoiceNumber) {
        return """
                {"specVersion": 1, "eventId": "%s", "invoiceNumber": "%s",
                 "creditorIban": "CH9300762011623852957", "debtorName": "Synthetic Customer AG",
                 "amount": "480.00", "currency": "CHF", "reference": null, "dueDate": "2026-09-30"}
                """.formatted(eventId, invoiceNumber);
    }

    private static void awaitTrue(BooleanSupplier condition) throws InterruptedException {
        Instant deadline = Instant.now().plusSeconds(60);
        while (!condition.getAsBoolean()) {
            assertThat(Instant.now()).as("waited 60 s for the listener").isBefore(deadline);
            Thread.sleep(200);
        }
    }
}

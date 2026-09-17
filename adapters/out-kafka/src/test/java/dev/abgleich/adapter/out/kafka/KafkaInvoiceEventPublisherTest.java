package dev.abgleich.adapter.out.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.abgleich.application.events.port.out.EventPublishingException;
import dev.abgleich.domain.account.Iban;
import dev.abgleich.domain.invoice.Invoice;
import dev.abgleich.domain.invoice.InvoiceEvent;
import dev.abgleich.domain.invoice.InvoiceNumber;
import dev.abgleich.domain.money.Money;
import dev.abgleich.domain.reference.PaymentReference;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.apache.kafka.clients.producer.MockProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;

class KafkaInvoiceEventPublisherTest {

    private static final Instant NOW = Instant.parse("2026-09-16T06:00:00Z");
    private static final UUID EVENT_ID = UUID.fromString("7b0e1f7c-3a52-4c1e-9d59-0d3c8f1a2b3c");
    private static final UUID INVOICE_ID = UUID.fromString("0f8a7d6c-5b4a-4938-8271-605f4e3d2c1b");

    @Test
    void B01_payload_keeps_amounts_as_decimal_strings_and_the_record_is_keyed_by_invoice() {
        MockProducer<String, String> producer = new MockProducer<>(true, null, new StringSerializer(), new StringSerializer());
        KafkaInvoiceEventPublisher publisher = publisher(producer);

        publisher.publish(paid());

        assertThat(producer.history()).singleElement().satisfies(record -> {
            assertThat(record.topic()).isEqualTo("abgleich.invoice-events");
            assertThat(record.key()).isEqualTo(INVOICE_ID.toString());
            assertThat(record.value()).isEqualTo("{\"specVersion\":1,\"type\":\"InvoicePaid\","
                    + "\"eventId\":\"7b0e1f7c-3a52-4c1e-9d59-0d3c8f1a2b3c\",\"occurredAt\":\"2026-09-16T06:00:00Z\","
                    + "\"invoiceId\":\"0f8a7d6c-5b4a-4938-8271-605f4e3d2c1b\",\"invoiceNumber\":\"F-2026-0142\","
                    + "\"creditorIban\":\"CH9300762011623852957\",\"currency\":\"CHF\",\"amount\":\"1250.00\","
                    + "\"paidAmount\":\"1250.00\",\"status\":\"PAID\"}");
            assertThat(header(record, KafkaInvoiceEventPublisher.EVENT_ID_HEADER)).isEqualTo(EVENT_ID.toString());
            assertThat(header(record, KafkaInvoiceEventPublisher.EVENT_TYPE_HEADER)).isEqualTo("InvoicePaid");
        });
    }

    @Test
    void B39_broker_failure_becomes_an_application_exception() {
        MockProducer<String, String> producer = new MockProducer<>(false, null, new StringSerializer(), new StringSerializer());
        KafkaInvoiceEventPublisher publisher = publisher(producer);
        Thread broker = Thread.ofVirtual().start(() -> {
            while (!producer.errorNext(new org.apache.kafka.common.errors.TimeoutException("no broker"))) {
                Thread.onSpinWait();
            }
        });

        assertThatThrownBy(() -> publisher.publish(paid()))
                .isInstanceOf(EventPublishingException.class)
                .hasMessageContaining("no broker");
        broker.interrupt();
    }

    @Test
    void B23_an_unacknowledged_event_is_a_failure_not_a_success() {
        MockProducer<String, String> producer = new MockProducer<>(false, null, new StringSerializer(), new StringSerializer());
        KafkaInvoiceEventPublisher publisher = new KafkaInvoiceEventPublisher(template(producer),
                "abgleich.invoice-events", Duration.ofMillis(50));

        assertThatThrownBy(() -> publisher.publish(paid()))
                .isInstanceOf(EventPublishingException.class)
                .hasMessageContaining("did not acknowledge");
    }

    private static KafkaInvoiceEventPublisher publisher(MockProducer<String, String> producer) {
        return new KafkaInvoiceEventPublisher(template(producer), "abgleich.invoice-events", Duration.ofSeconds(5));
    }

    private static KafkaTemplate<String, String> template(MockProducer<String, String> producer) {
        ProducerFactory<String, String> factory = () -> producer;
        return new KafkaTemplate<>(factory);
    }

    private static InvoiceEvent paid() {
        Invoice open = Invoice.register(INVOICE_ID, InvoiceNumber.of("F-2026-0142"), Iban.of("CH9300762011623852957"),
                "Keller GmbH", Money.chf("1250.00"), PaymentReference.none(), LocalDate.of(2026, 9, 30));
        return InvoiceEvent.between(open, open.withConfirmedPayment(Money.chf("1250.00")), () -> EVENT_ID, NOW)
                .orElseThrow();
    }

    private static String header(ProducerRecord<String, String> record, String name) {
        return new String(record.headers().lastHeader(name).value(), StandardCharsets.UTF_8);
    }
}

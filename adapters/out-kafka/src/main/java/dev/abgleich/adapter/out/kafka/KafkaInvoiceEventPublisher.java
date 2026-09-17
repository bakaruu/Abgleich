package dev.abgleich.adapter.out.kafka;

import dev.abgleich.application.events.port.out.EventPublisherPort;
import dev.abgleich.application.events.port.out.EventPublishingException;
import dev.abgleich.domain.invoice.InvoiceEvent;
import dev.abgleich.domain.invoice.InvoiceEvent.InvoicePaid;
import dev.abgleich.domain.invoice.InvoiceEvent.InvoiceReopened;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.springframework.kafka.KafkaException;
import org.springframework.kafka.core.KafkaTemplate;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Publishes invoice events as JSON. The record key is the invoice id, so all events of one invoice land in
 * the same partition and keep their order. The event id travels in the payload and in a header: consumers
 * deduplicate by it, because the outbox relay delivers at least once (B23).
 *
 * <p>Amounts are strings with two decimals, never JSON numbers, which many consumers read as floating point
 * (B01).
 */
public final class KafkaInvoiceEventPublisher implements EventPublisherPort {

    static final String EVENT_ID_HEADER = "abgleich-event-id";
    static final String EVENT_TYPE_HEADER = "abgleich-event-type";
    static final int SPEC_VERSION = 1;

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final KafkaTemplate<String, String> kafka;
    private final String topic;
    private final Duration acknowledgementTimeout;

    public KafkaInvoiceEventPublisher(KafkaTemplate<String, String> kafka, String topic, Duration acknowledgementTimeout) {
        this.kafka = Objects.requireNonNull(kafka, "kafka");
        this.topic = Objects.requireNonNull(topic, "topic");
        this.acknowledgementTimeout = Objects.requireNonNull(acknowledgementTimeout, "acknowledgementTimeout");
    }

    @Override
    public void publish(InvoiceEvent event) {
        ProducerRecord<String, String> record = new ProducerRecord<>(topic, event.invoiceId().toString(), toJson(event));
        record.headers().add(EVENT_ID_HEADER, event.eventId().toString().getBytes(StandardCharsets.UTF_8));
        record.headers().add(EVENT_TYPE_HEADER, type(event).getBytes(StandardCharsets.UTF_8));
        try {
            kafka.send(record).get(acknowledgementTimeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new EventPublishingException("Interrupted while waiting for Kafka to acknowledge the event", e);
        } catch (ExecutionException | KafkaException e) {
            throw new EventPublishingException("Kafka did not accept the event: " + rootMessage(e), e);
        } catch (TimeoutException e) {
            throw new EventPublishingException("Kafka did not acknowledge the event within " + acknowledgementTimeout, e);
        }
    }

    /** The published payload. Contains the creditor IBAN, which is the company's own account, and no debtor data. */
    static String toJson(InvoiceEvent event) {
        ObjectNode json = JSON.createObjectNode();
        json.put("specVersion", SPEC_VERSION);
        json.put("type", type(event));
        json.put("eventId", event.eventId().toString());
        json.put("occurredAt", event.occurredAt().toString());
        json.put("invoiceId", event.invoiceId().toString());
        json.put("invoiceNumber", event.number().value());
        json.put("creditorIban", event.creditorAccount().value());
        json.put("currency", event.amount().currency().getCurrencyCode());
        json.put("amount", event.amount().amount().toPlainString());
        json.put("paidAmount", event.paidAmount().amount().toPlainString());
        json.put("status", event.status().name());
        return JSON.writeValueAsString(json);
    }

    static String type(InvoiceEvent event) {
        return switch (event) {
            case InvoicePaid paid -> "InvoicePaid";
            case InvoiceReopened reopened -> "InvoiceReopened";
        };
    }

    private static String rootMessage(Throwable failure) {
        Throwable root = failure;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        return root.getClass().getSimpleName() + (root.getMessage() == null ? "" : " " + root.getMessage());
    }
}

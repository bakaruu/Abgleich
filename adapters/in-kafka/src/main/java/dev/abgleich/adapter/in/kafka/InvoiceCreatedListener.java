package dev.abgleich.adapter.in.kafka;

import dev.abgleich.application.invoice.InvoiceReceipt;
import dev.abgleich.application.invoice.MessageChannel;
import dev.abgleich.application.invoice.port.in.ReceiveInvoiceUseCase;
import dev.abgleich.domain.invoice.InvalidInvoiceException;
import dev.abgleich.domain.money.CurrencyMismatchException;
import dev.abgleich.domain.reference.InvalidReferenceException;
import java.util.Objects;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;

/**
 * Consumes {@code InvoiceCreated} events from the ERP. Kafka delivers at least once; the use case recognises a
 * redelivered event by its id (B24). A message that can never be processed is rejected with
 * {@link RejectedMessageException}, which the error handler sends to the dead letter topic instead of retrying
 * it forever and blocking the partition.
 *
 * <p>Logs name the invoice number and the outcome, never the debtor name or IBAN (B41).
 */
public final class InvoiceCreatedListener {

    private static final Logger log = LoggerFactory.getLogger(InvoiceCreatedListener.class);

    private final ReceiveInvoiceUseCase receiveInvoice;

    public InvoiceCreatedListener(ReceiveInvoiceUseCase receiveInvoice) {
        this.receiveInvoice = Objects.requireNonNull(receiveInvoice, "receiveInvoice");
    }

    @KafkaListener(id = "invoice-created", topics = "${abgleich.kafka.topics.invoice-created}",
            groupId = "${abgleich.kafka.consumer-group}")
    public void onMessage(ConsumerRecord<String, String> record) {
        InvoiceCreatedMessage message = InvoiceCreatedMessage.parse(record.value());
        InvoiceReceipt receipt;
        try {
            receipt = receiveInvoice.receive(MessageChannel.KAFKA, message.eventId(), message.command());
        } catch (InvalidInvoiceException | InvalidReferenceException | CurrencyMismatchException
                | IllegalArgumentException invalid) {
            throw new RejectedMessageException("The invoice in the message is not valid: " + invalid.getMessage(), invalid);
        }
        switch (receipt.outcome()) {
            case REGISTERED -> log.info("Invoice {} {} from event {}", message.command().number(),
                    receipt.redelivered() ? "was already registered" : "registered", message.eventId());
            case DUPLICATE_NUMBER -> log.warn("Invoice {} from event {} already exists; nothing stored",
                    message.command().number(), message.eventId());
            case ID_REUSED -> log.warn("Event id {} was already used for another invoice; invoice {} not stored",
                    message.eventId(), message.command().number());
        }
    }
}

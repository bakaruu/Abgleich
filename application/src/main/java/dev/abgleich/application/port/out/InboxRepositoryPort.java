package dev.abgleich.application.port.out;

import dev.abgleich.application.port.in.InvoiceReceipt;
import dev.abgleich.application.port.in.MessageChannel;
import dev.abgleich.domain.invoice.Invoice;
import java.time.Instant;

/** Invoices that arrive as messages: a Kafka event or a REST request with an idempotency key. */
public interface InboxRepositoryPort {

    /**
     * In one transaction, records the message as processed and stores the invoice. The database decides,
     * not a prior query: a message already recorded for the channel changes nothing and returns the receipt
     * of its first processing, marked as redelivered (B24). An invoice number that already exists is not
     * stored again either; the receipt then says {@link InvoiceReceipt.Outcome#DUPLICATE_NUMBER}.
     *
     * @throws StorageException if the database fails
     */
    InvoiceReceipt registerOnce(MessageChannel channel, String messageId, Invoice invoice, Instant receivedAt);
}

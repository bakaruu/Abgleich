package dev.abgleich.application.port.in;

import dev.abgleich.domain.invoice.InvalidInvoiceException;
import java.util.regex.Pattern;

/**
 * Registers an invoice that arrived as a message. Messages are delivered at least once, so the same
 * message may arrive again; it is then recognised by its id and changes nothing (B24).
 */
public interface ReceiveInvoiceUseCase {

    /** 1 to 64 letters, digits or {@code . _ : -}: a UUID, a Kafka event id or an idempotency key. */
    Pattern MESSAGE_ID = Pattern.compile("[A-Za-z0-9._:-]{1,64}");

    /**
     * @throws InvalidInvoiceException if the invoice breaks a business rule; the message is not recorded
     * @throws IllegalArgumentException if the message id does not match {@link #MESSAGE_ID}
     */
    InvoiceReceipt receive(MessageChannel channel, String messageId, RegisterInvoiceCommand command);
}

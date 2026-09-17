package dev.abgleich.application.events.port.out;

import dev.abgleich.domain.invoice.InvoiceEvent;

/** Hands an event to the message broker. Delivery is at least once: a consumer may see an event twice. */
public interface EventPublisherPort {

    /**
     * Returns only after the broker acknowledged the event.
     *
     * @throws EventPublishingException if the broker did not acknowledge it
     */
    void publish(InvoiceEvent event);
}

package dev.abgleich.application.service;

import dev.abgleich.domain.invoice.Invoice;
import dev.abgleich.domain.invoice.InvoiceEvent;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/** The events caused by changing invoices, stored together with the change (B23). */
final class InvoiceEvents {

    private InvoiceEvents() {
    }

    /**
     * @param read the invoices as they were read
     * @param changed the same invoices after the change
     */
    static List<InvoiceEvent> between(List<Invoice> read, List<Invoice> changed, Instant occurredAt) {
        Map<UUID, Invoice> before = read.stream()
                .collect(Collectors.toMap(Invoice::id, Function.identity(), (first, second) -> first));
        return changed.stream()
                .map(after -> InvoiceEvent.between(
                        Objects.requireNonNull(before.get(after.id()), "changed invoice was not read"),
                        after, UUID::randomUUID, occurredAt))
                .flatMap(Optional::stream)
                .toList();
    }
}

package dev.abgleich.domain.matching;

import dev.abgleich.domain.invoice.InvalidInvoiceException;
import dev.abgleich.domain.invoice.Invoice;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * What confirmed allocations do to their invoices. Shared by the automatic confirmation and a person's
 * confirmation, so both settle invoices exactly the same way.
 */
public final class Settlement {

    private Settlement() {
    }

    /**
     * The invoices after applying the confirmed allocations, each one derived from the version that was read, so
     * storing them fails if someone changed an invoice in between (B22).
     *
     * @throws InvalidInvoiceException if an invoice cannot take the payment, for example because it is cancelled (B31)
     * @throws NullPointerException if an allocation names an invoice that was not read
     */
    public static List<Invoice> apply(List<Allocation> confirmed, List<Invoice> invoicesRead) {
        Map<UUID, Invoice> byId = invoicesRead.stream().collect(Collectors.toMap(Invoice::id, Function.identity()));
        List<Invoice> settled = new ArrayList<>();
        for (Allocation allocation : confirmed) {
            Invoice invoice = Objects.requireNonNull(byId.get(allocation.invoiceId()), "allocated invoice was not read");
            settled.add(invoice.withConfirmedPayment(allocation.settledAmount()));
        }
        return settled;
    }
}

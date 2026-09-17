package dev.abgleich.application.invoice.port.in;

import dev.abgleich.application.invoice.InvoiceDetail;
import dev.abgleich.application.invoice.InvoiceView;
import dev.abgleich.domain.invoice.InvoiceStatus;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Read side of the invoices screen and API. */
public interface InvoiceQuery {

    /** @param status only invoices in this status, or all when {@code null}; ordered by number */
    List<InvoiceView> list(InvoiceStatus status, int limit);

    Optional<InvoiceDetail> detail(UUID invoiceId);
}

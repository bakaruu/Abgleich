package dev.abgleich.application.port.out;

import dev.abgleich.application.port.in.InvoiceQuery.InvoiceDetail;
import dev.abgleich.application.port.in.InvoiceQuery.InvoiceView;
import dev.abgleich.application.port.in.ReviewQueueQuery.ReviewItem;
import dev.abgleich.domain.invoice.InvoiceStatus;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Read models for the review queue and the invoices, served by the persistence adapter. */
public interface ReconciliationQueriesPort {

    List<ReviewItem> pendingReview(int limit);

    long pendingReviewCount();

    List<InvoiceView> invoices(InvoiceStatus status, int limit);

    Optional<InvoiceDetail> invoiceDetail(UUID invoiceId);
}

package dev.abgleich.application.reconciliation.service;

import dev.abgleich.application.invoice.InvoiceDetail;
import dev.abgleich.application.invoice.InvoiceView;
import dev.abgleich.application.invoice.port.in.InvoiceQuery;
import dev.abgleich.application.reconciliation.ReviewItem;
import dev.abgleich.application.reconciliation.port.in.ReviewQueueQuery;
import dev.abgleich.application.reconciliation.port.out.ReconciliationQueriesPort;
import dev.abgleich.domain.invoice.InvoiceStatus;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Keeps inbound adapters away from the persistence adapter: they only see these queries (B40). */
public final class ReconciliationQueriesService implements ReviewQueueQuery, InvoiceQuery {

    static final int MAX_LIMIT = 500;

    private final ReconciliationQueriesPort queries;

    public ReconciliationQueriesService(ReconciliationQueriesPort queries) {
        this.queries = Objects.requireNonNull(queries, "queries");
    }

    @Override
    public List<ReviewItem> pending(int limit) {
        return queries.pendingReview(bounded(limit));
    }

    @Override
    public long pendingCount() {
        return queries.pendingReviewCount();
    }

    @Override
    public List<InvoiceView> list(InvoiceStatus status, int limit) {
        return queries.invoices(status, bounded(limit));
    }

    @Override
    public Optional<InvoiceDetail> detail(UUID invoiceId) {
        return queries.invoiceDetail(Objects.requireNonNull(invoiceId, "invoiceId"));
    }

    private static int bounded(int limit) {
        return Math.clamp(limit, 1, MAX_LIMIT);
    }
}

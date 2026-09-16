package dev.abgleich.application.port.in;

import java.util.UUID;

public interface CancelInvoiceUseCase {

    /** Refused while the invoice has payments; stale if it changed since {@code expectedVersion} was shown. */
    DecisionResult cancel(UUID invoiceId, long expectedVersion);
}

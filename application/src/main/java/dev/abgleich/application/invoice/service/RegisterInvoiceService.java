package dev.abgleich.application.invoice.service;

import dev.abgleich.application.DecisionResult;
import dev.abgleich.application.DecisionResult.Outcome;
import dev.abgleich.application.StaleDataException;
import dev.abgleich.application.invoice.RegisterInvoiceCommand;
import dev.abgleich.application.invoice.port.in.CancelInvoiceUseCase;
import dev.abgleich.application.invoice.port.in.RegisterInvoiceUseCase;
import dev.abgleich.application.invoice.port.out.InvoiceRepositoryPort;
import dev.abgleich.application.reconciliation.port.in.ReconcileUseCase;
import dev.abgleich.domain.invoice.InvalidInvoiceException;
import dev.abgleich.domain.invoice.Invoice;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public final class RegisterInvoiceService implements RegisterInvoiceUseCase, CancelInvoiceUseCase {

    private final InvoiceRepositoryPort invoices;
    private final ReconcileUseCase reconcile;

    public RegisterInvoiceService(InvoiceRepositoryPort invoices, ReconcileUseCase reconcile) {
        this.invoices = Objects.requireNonNull(invoices, "invoices");
        this.reconcile = Objects.requireNonNull(reconcile, "reconcile");
    }

    /**
     * B30: a payment can arrive before its invoice. Once the invoice is stored (its own transaction),
     * the pending payments of the creditor account are reconciled again, so they do not stay unmatched.
     */
    @Override
    public UUID register(RegisterInvoiceCommand command) {
        Invoice invoice = command.newInvoice(UUID.randomUUID());
        invoices.add(invoice);
        reconcile.reconcilePending(invoice.creditorAccount());
        return invoice.id();
    }

    @Override
    public DecisionResult cancel(UUID invoiceId, long expectedVersion) {
        Optional<Invoice> found = invoices.findById(invoiceId);
        if (found.isEmpty()) {
            return new DecisionResult(Outcome.NOT_FOUND, "This invoice does not exist.");
        }
        Invoice invoice = found.get();
        if (invoice.cancelled()) {
            return new DecisionResult(Outcome.ALREADY_DONE, "Invoice " + invoice.number() + " is already cancelled.");
        }
        if (invoice.version() != expectedVersion) {
            return new DecisionResult(Outcome.STALE,
                    "Invoice " + invoice.number() + " changed since it was shown. Reload to see its current state.");
        }
        try {
            invoices.update(invoice.cancel());
        } catch (InvalidInvoiceException refused) {
            return new DecisionResult(Outcome.REFUSED, refused.getMessage());
        } catch (StaleDataException raced) {
            return new DecisionResult(Outcome.STALE,
                    "Invoice " + invoice.number() + " changed since it was shown. Reload to see its current state.");
        }
        return DecisionResult.done("Invoice " + invoice.number() + " cancelled.");
    }
}

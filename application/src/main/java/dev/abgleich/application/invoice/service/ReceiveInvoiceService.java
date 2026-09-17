package dev.abgleich.application.invoice.service;

import dev.abgleich.application.invoice.InvoiceReceipt;
import dev.abgleich.application.invoice.MessageChannel;
import dev.abgleich.application.invoice.RegisterInvoiceCommand;
import dev.abgleich.application.invoice.port.in.ReceiveInvoiceUseCase;
import dev.abgleich.application.invoice.port.out.InboxRepositoryPort;
import dev.abgleich.application.reconciliation.port.in.ReconcileUseCase;
import dev.abgleich.domain.invoice.Invoice;
import java.time.Clock;
import java.util.Objects;
import java.util.UUID;

/**
 * B24: messages arrive at least once. The inbox records every processed message id in the same transaction
 * as the invoice, so a redelivered message is recognised by the database, not by a query that two deliveries
 * running at the same moment would both pass.
 */
public final class ReceiveInvoiceService implements ReceiveInvoiceUseCase {

    private final InboxRepositoryPort inbox;
    private final ReconcileUseCase reconcile;
    private final Clock clock;

    public ReceiveInvoiceService(InboxRepositoryPort inbox, ReconcileUseCase reconcile, Clock clock) {
        this.inbox = Objects.requireNonNull(inbox, "inbox");
        this.reconcile = Objects.requireNonNull(reconcile, "reconcile");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public InvoiceReceipt receive(MessageChannel channel, String messageId, RegisterInvoiceCommand command) {
        Objects.requireNonNull(channel, "channel");
        Objects.requireNonNull(command, "command");
        if (messageId == null || !MESSAGE_ID.matcher(messageId).matches()) {
            throw new IllegalArgumentException("A message id has 1 to 64 letters, digits or . _ : -");
        }
        Invoice invoice = command.newInvoice(UUID.randomUUID());
        InvoiceReceipt receipt = inbox.registerOnce(channel, messageId, invoice, clock.instant());
        if (receipt.outcome() == InvoiceReceipt.Outcome.REGISTERED && !receipt.redelivered()) {
            // B30: payments that arrived before this invoice are matched now that it exists.
            reconcile.reconcilePending(invoice.creditorAccount());
        }
        return receipt;
    }
}

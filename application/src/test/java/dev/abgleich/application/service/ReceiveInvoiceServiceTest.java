package dev.abgleich.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.abgleich.application.port.in.InvoiceReceipt;
import dev.abgleich.application.port.in.InvoiceReceipt.Outcome;
import dev.abgleich.application.port.in.MessageChannel;
import dev.abgleich.application.port.in.ReconcileUseCase;
import dev.abgleich.application.port.in.ReconciliationRun;
import dev.abgleich.application.port.in.RegisterInvoiceCommand;
import dev.abgleich.application.port.out.InboxRepositoryPort;
import dev.abgleich.domain.account.Iban;
import dev.abgleich.domain.invoice.InvalidInvoiceException;
import dev.abgleich.domain.invoice.Invoice;
import dev.abgleich.domain.invoice.InvoiceNumber;
import dev.abgleich.domain.money.Money;
import dev.abgleich.domain.reference.PaymentReference;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ReceiveInvoiceServiceTest {

    private static final Iban ACCOUNT = Iban.of("CH9300762011623852957");
    private static final Instant NOW = Instant.parse("2026-09-16T10:00:00Z");

    private final FakeInbox inbox = new FakeInbox();
    private final List<Iban> reconciled = new ArrayList<>();
    private final ReconcileUseCase reconcile = account -> {
        reconciled.add(account);
        return ReconciliationRun.empty();
    };
    private final ReceiveInvoiceService service =
            new ReceiveInvoiceService(inbox, reconcile, Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void registers_the_invoice_and_reconciles_payments_that_arrived_before_it() {
        InvoiceReceipt receipt = service.receive(MessageChannel.KAFKA, "evt-1", command("F-2026-0142"));

        assertThat(receipt.outcome()).isEqualTo(Outcome.REGISTERED);
        assertThat(receipt.redelivered()).isFalse();
        assertThat(inbox.invoices).containsKey(receipt.invoiceId());
        assertThat(reconciled).as("B30").containsExactly(ACCOUNT);
    }

    @Test
    void B24_redelivered_event_is_noop() {
        InvoiceReceipt first = service.receive(MessageChannel.KAFKA, "evt-1", command("F-2026-0142"));

        InvoiceReceipt second = service.receive(MessageChannel.KAFKA, "evt-1", command("F-2026-0142"));

        assertThat(second).isEqualTo(new InvoiceReceipt(Outcome.REGISTERED, first.invoiceId(), true));
        assertThat(inbox.invoices).hasSize(1);
        assertThat(reconciled).as("nothing new to reconcile").hasSize(1);
    }

    @Test
    void invalid_invoice_or_message_id_is_refused_before_anything_is_stored() {
        assertThatThrownBy(() -> service.receive(MessageChannel.KAFKA, "evt-1", new RegisterInvoiceCommand(
                InvoiceNumber.of("F-2026-0142"), ACCOUNT, " ", Money.chf("480.00"), PaymentReference.none(),
                LocalDate.of(2026, 9, 30)))).isInstanceOf(InvalidInvoiceException.class);
        assertThatThrownBy(() -> service.receive(MessageChannel.REST, "../etc/passwd", command("F-2026-0142")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.receive(MessageChannel.REST, "k".repeat(65), command("F-2026-0142")))
                .isInstanceOf(IllegalArgumentException.class);

        assertThat(inbox.invoices).isEmpty();
    }

    private static RegisterInvoiceCommand command(String number) {
        return new RegisterInvoiceCommand(InvoiceNumber.of(number), ACCOUNT, "Keller GmbH", Money.chf("480.00"),
                PaymentReference.none(), LocalDate.of(2026, 9, 30));
    }

    /** Behaves like the database: the first message id per channel wins. */
    private static final class FakeInbox implements InboxRepositoryPort {
        private final Map<UUID, Invoice> invoices = new HashMap<>();
        private final Map<String, InvoiceReceipt> processed = new HashMap<>();

        @Override
        public InvoiceReceipt registerOnce(MessageChannel channel, String messageId, Invoice invoice,
                Instant receivedAt) {
            InvoiceReceipt earlier = processed.get(channel + "/" + messageId);
            if (earlier != null) {
                return new InvoiceReceipt(earlier.outcome(), earlier.invoiceId(), true);
            }
            invoices.put(invoice.id(), invoice);
            InvoiceReceipt receipt = new InvoiceReceipt(Outcome.REGISTERED, invoice.id(), false);
            processed.put(channel + "/" + messageId, receipt);
            return receipt;
        }
    }
}

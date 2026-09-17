package dev.abgleich.application.invoice.service;

import static org.assertj.core.api.Assertions.assertThat;

import dev.abgleich.application.DecisionResult;
import dev.abgleich.application.DecisionResult.Outcome;
import dev.abgleich.application.invoice.RegisterInvoiceCommand;
import dev.abgleich.application.invoice.port.out.InvoiceRepositoryPort;
import dev.abgleich.application.reconciliation.port.in.ReconciliationRun;
import dev.abgleich.domain.account.Iban;
import dev.abgleich.domain.invoice.Invoice;
import dev.abgleich.domain.invoice.InvoiceNumber;
import dev.abgleich.domain.invoice.InvoiceStatus;
import dev.abgleich.domain.money.Money;
import dev.abgleich.domain.reference.PaymentReference;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Currency;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RegisterInvoiceServiceTest {

    private static final Iban ACCOUNT = Iban.of("CH9300762011623852957");

    private final Map<UUID, Invoice> stored = new HashMap<>();
    private final List<Iban> reconciled = new ArrayList<>();
    private final RegisterInvoiceService service = new RegisterInvoiceService(new FakeInvoices(), account -> {
        reconciled.add(account);
        return ReconciliationRun.empty();
    });

    @Test
    void B30_new_invoice_rematches_pending_payments_of_its_account() {
        UUID id = service.register(command());

        assertThat(stored).containsKey(id);
        assertThat(reconciled).containsExactly(ACCOUNT);
    }

    @Test
    void cancels_an_unpaid_invoice_at_the_version_shown() {
        UUID id = service.register(command());

        DecisionResult result = service.cancel(id, 0);

        assertThat(result.outcome()).isEqualTo(Outcome.DONE);
        assertThat(stored.get(id).status()).isEqualTo(InvoiceStatus.CANCELLED);
        assertThat(service.cancel(id, 0).outcome()).isEqualTo(Outcome.ALREADY_DONE);
    }

    @Test
    void B31_invoice_with_payments_is_not_cancelled() {
        UUID id = service.register(command());
        stored.put(id, stored.get(id).withConfirmedPayment(Money.chf("100.00")));

        DecisionResult result = service.cancel(id, 0);

        assertThat(result.outcome()).isEqualTo(Outcome.REFUSED);
        assertThat(stored.get(id).status()).isEqualTo(InvoiceStatus.PARTIALLY_PAID);
    }

    @Test
    void B34_cancelling_an_invoice_that_changed_since_it_was_shown_is_stale() {
        UUID id = service.register(command());

        assertThat(service.cancel(id, 7).outcome()).isEqualTo(Outcome.STALE);
    }

    private static RegisterInvoiceCommand command() {
        return new RegisterInvoiceCommand(InvoiceNumber.of("F-2026-0142"), ACCOUNT, "Keller GmbH", Money.chf("480.00"),
                PaymentReference.none(), LocalDate.of(2026, 9, 30));
    }

    private final class FakeInvoices implements InvoiceRepositoryPort {
        @Override
        public void add(Invoice invoice) {
            stored.put(invoice.id(), invoice);
        }

        @Override
        public Optional<Invoice> findById(UUID id) {
            return Optional.ofNullable(stored.get(id));
        }

        @Override
        public List<Invoice> findCandidates(Iban creditorAccount, Currency currency, PaymentReference reference) {
            return List.copyOf(stored.values());
        }

        @Override
        public void update(Invoice invoice) {
            stored.put(invoice.id(), invoice);
        }
    }
}

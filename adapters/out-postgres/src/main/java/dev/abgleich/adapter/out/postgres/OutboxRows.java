package dev.abgleich.adapter.out.postgres;

import dev.abgleich.domain.account.Iban;
import dev.abgleich.domain.invoice.InvoiceEvent;
import dev.abgleich.domain.invoice.InvoiceEvent.InvoicePaid;
import dev.abgleich.domain.invoice.InvoiceEvent.InvoiceReopened;
import dev.abgleich.domain.invoice.InvoiceNumber;
import dev.abgleich.domain.invoice.InvoiceStatus;
import dev.abgleich.domain.money.Money;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Currency;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Maps invoice events to outbox rows. Callers insert them inside the transaction of the change (B23). */
final class OutboxRows {

    static final String COLUMNS = """
            id, event_type, invoice_id, invoice_number, creditor_iban, currency, amount, paid_amount, invoice_status,
            occurred_at""";

    private OutboxRows() {
    }

    static void insert(JdbcClient client, InvoiceEvent event) {
        client.sql("insert into outbox_event (" + COLUMNS + ") values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")
                .params(event.eventId(), type(event), event.invoiceId(), event.number().value(),
                        event.creditorAccount().value(), event.amount().currency().getCurrencyCode(),
                        event.amount().amount(), event.paidAmount().amount(), event.status().name(),
                        OffsetDateTime.ofInstant(event.occurredAt(), ZoneOffset.UTC))
                .update();
    }

    static InvoiceEvent map(ResultSet rs) throws SQLException {
        Currency currency = Currency.getInstance(rs.getString("currency"));
        UUID id = rs.getObject("id", UUID.class);
        UUID invoiceId = rs.getObject("invoice_id", UUID.class);
        InvoiceNumber number = InvoiceNumber.of(rs.getString("invoice_number"));
        Iban account = Iban.of(rs.getString("creditor_iban"));
        Money amount = new Money(rs.getBigDecimal("amount"), currency);
        Money paid = new Money(rs.getBigDecimal("paid_amount"), currency);
        InvoiceStatus status = InvoiceStatus.valueOf(rs.getString("invoice_status"));
        Instant occurredAt = rs.getObject("occurred_at", OffsetDateTime.class).toInstant();
        return switch (rs.getString("event_type")) {
            case "INVOICE_PAID" -> new InvoicePaid(id, invoiceId, number, account, amount, paid, status, occurredAt);
            case "INVOICE_REOPENED" -> new InvoiceReopened(id, invoiceId, number, account, amount, paid, status, occurredAt);
            default -> throw new IllegalStateException("Unknown outbox event type " + rs.getString("event_type"));
        };
    }

    private static String type(InvoiceEvent event) {
        return switch (event) {
            case InvoicePaid paid -> "INVOICE_PAID";
            case InvoiceReopened reopened -> "INVOICE_REOPENED";
        };
    }
}

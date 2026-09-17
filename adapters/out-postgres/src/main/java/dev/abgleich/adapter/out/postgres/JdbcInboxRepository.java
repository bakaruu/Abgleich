package dev.abgleich.adapter.out.postgres;

import dev.abgleich.application.StorageException;
import dev.abgleich.application.invoice.InvoiceReceipt;
import dev.abgleich.application.invoice.InvoiceReceipt.Outcome;
import dev.abgleich.application.invoice.MessageChannel;
import dev.abgleich.application.invoice.port.out.InboxRepositoryPort;
import dev.abgleich.domain.invoice.Invoice;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * B24 without a prior "was it processed?" query. In one transaction: insert the invoice unless its number
 * exists, then insert the message id. If another delivery of the same message committed first, the second
 * insert finds its row, the whole transaction rolls back (including an invoice inserted a moment before) and
 * the stored receipt is returned. Two deliveries running at the same moment wait for each other on the
 * unique indexes, so exactly one of them stores anything.
 */
public final class JdbcInboxRepository implements InboxRepositoryPort {

    private final JdbcClient client;
    private final TransactionTemplate transactions;

    public JdbcInboxRepository(DataSource dataSource, TransactionTemplate transactions) {
        this.client = JdbcClient.create(dataSource);
        this.transactions = transactions;
    }

    @Override
    public InvoiceReceipt registerOnce(MessageChannel channel, String messageId, Invoice invoice, Instant receivedAt) {
        try {
            Optional<InvoiceReceipt> stored = transactions.execute(status -> {
                Outcome outcome = insertInvoice(invoice) ? Outcome.REGISTERED : Outcome.DUPLICATE_NUMBER;
                UUID invoiceId = outcome == Outcome.REGISTERED ? invoice.id() : idOfNumber(invoice);
                int recorded = client.sql("""
                                insert into processed_message (channel, message_id, invoice_id, outcome, processed_at)
                                values (?, ?, ?, ?, ?)
                                on conflict (channel, message_id) do nothing
                                """)
                        .params(channel.name(), messageId, invoiceId, outcome.name(),
                                OffsetDateTime.ofInstant(receivedAt, ZoneOffset.UTC))
                        .update();
                if (recorded == 0) {
                    status.setRollbackOnly();
                    return Optional.empty();
                }
                return Optional.of(new InvoiceReceipt(outcome, invoiceId, false));
            });
            return stored != null && stored.isPresent() ? stored.get() : earlierReceipt(channel, messageId, invoice);
        } catch (DataAccessException e) {
            throw new StorageException("The received invoice could not be stored", e);
        }
    }

    private boolean insertInvoice(Invoice invoice) {
        return client.sql("insert into invoice (" + JdbcInvoiceRepository.COLUMNS + """
                        ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        on conflict (invoice_number) do nothing
                        """)
                .params(JdbcInvoiceRepository.values(invoice))
                .update() == 1;
    }

    private UUID idOfNumber(Invoice invoice) {
        return client.sql("select id from invoice where invoice_number = ?")
                .param(invoice.number().value())
                .query(UUID.class)
                .single();
    }

    /** The message was processed before: its first outcome, unless the id now names another invoice. */
    private InvoiceReceipt earlierReceipt(MessageChannel channel, String messageId, Invoice invoice) {
        Map<String, Object> row = client.sql("""
                        select m.invoice_id, m.outcome, i.invoice_number
                          from processed_message m join invoice i on i.id = m.invoice_id
                         where m.channel = ? and m.message_id = ?
                        """)
                .params(channel.name(), messageId)
                .query().singleRow();
        UUID invoiceId = (UUID) row.get("invoice_id");
        if (!invoice.number().value().equals(row.get("invoice_number"))) {
            return new InvoiceReceipt(Outcome.ID_REUSED, invoiceId, true);
        }
        return new InvoiceReceipt(Outcome.valueOf((String) row.get("outcome")), invoiceId, true);
    }
}

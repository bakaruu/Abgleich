package dev.abgleich.adapter.out.postgres;

import dev.abgleich.application.port.out.DuplicateInvoiceException;
import dev.abgleich.application.port.out.InvoiceRepositoryPort;
import dev.abgleich.application.port.out.StaleDataException;
import dev.abgleich.application.port.out.StorageException;
import dev.abgleich.domain.account.Iban;
import dev.abgleich.domain.invoice.Invoice;
import dev.abgleich.domain.invoice.InvoiceNumber;
import dev.abgleich.domain.invoice.InvoiceStatus;
import dev.abgleich.domain.money.Money;
import dev.abgleich.domain.reference.PaymentReference;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.Collection;
import java.util.Currency;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;

public final class JdbcInvoiceRepository implements InvoiceRepositoryPort {

    static final String COLUMNS = """
            id, invoice_number, creditor_iban, debtor_name, amount, currency, reference, due_date,
            paid_amount, status, version""";

    private final JdbcClient client;

    public JdbcInvoiceRepository(DataSource dataSource) {
        this.client = JdbcClient.create(dataSource);
    }

    @Override
    public void add(Invoice invoice) {
        try {
            client.sql("insert into invoice (" + COLUMNS + ") values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")
                    .params(values(invoice))
                    .update();
        } catch (DuplicateKeyException e) {
            throw new DuplicateInvoiceException("Invoice " + invoice.number() + " already exists", e);
        } catch (DataAccessException e) {
            throw new StorageException("The invoice could not be stored", e);
        }
    }

    /** Values for {@link #COLUMNS}, in that order. */
    static Object[] values(Invoice invoice) {
        return new Object[] {invoice.id(), invoice.number().value(), invoice.creditorAccount().value(),
                invoice.debtorName(), invoice.amount().amount(), invoice.amount().currency().getCurrencyCode(),
                References.text(invoice.reference()), invoice.dueDate(), invoice.paidAmount().amount(),
                invoice.status().name(), invoice.version()};
    }

    @Override
    public Optional<Invoice> findById(UUID id) {
        try {
            return client.sql("select " + COLUMNS + " from invoice where id = ?")
                    .param(id)
                    .query((rs, row) -> invoice(rs))
                    .optional();
        } catch (DataAccessException e) {
            throw new StorageException("The invoice could not be read", e);
        }
    }

    @Override
    public List<Invoice> findCandidates(Iban creditorAccount, Currency currency, PaymentReference reference) {
        try {
            return client.sql("select " + COLUMNS + """
                             from invoice
                            where creditor_iban = ? and currency = ?
                              and (status in ('OPEN', 'PARTIALLY_PAID') or (reference is not null and reference = ?))
                            order by invoice_number
                            """)
                    .params(creditorAccount.value(), currency.getCurrencyCode(), References.text(reference))
                    .query((rs, row) -> invoice(rs))
                    .list();
        } catch (DataAccessException e) {
            throw new StorageException("Candidate invoices could not be read", e);
        }
    }

    @Override
    public void update(Invoice invoice) {
        try {
            update(client, invoice);
        } catch (DataAccessException e) {
            throw new StorageException("The invoice could not be stored", e);
        }
    }

    /** Optimistic locking in SQL: the row changes only if nobody changed it since it was read (B22). */
    static void update(JdbcClient client, Invoice invoice) {
        int updated = client.sql("""
                        update invoice set paid_amount = ?, status = ?, version = version + 1
                         where id = ? and version = ?
                        """)
                .params(invoice.paidAmount().amount(), invoice.status().name(), invoice.id(), invoice.version())
                .update();
        if (updated != 1) {
            throw new StaleDataException("Invoice " + invoice.number() + " changed since it was read");
        }
    }

    static List<Invoice> findAll(JdbcClient client, Collection<UUID> ids) {
        if (ids.isEmpty()) {
            return List.of();
        }
        return client.sql("select " + COLUMNS + " from invoice where id in (:ids) order by invoice_number")
                .param("ids", List.copyOf(ids))
                .query((rs, row) -> invoice(rs))
                .list();
    }

    static Invoice invoice(ResultSet rs) throws SQLException {
        Currency currency = Currency.getInstance(rs.getString("currency"));
        return new Invoice(
                rs.getObject("id", UUID.class),
                InvoiceNumber.of(rs.getString("invoice_number")),
                Iban.of(rs.getString("creditor_iban")),
                rs.getString("debtor_name"),
                new Money(rs.getBigDecimal("amount"), currency),
                PaymentReference.parse(rs.getString("reference")),
                rs.getObject("due_date", LocalDate.class),
                new Money(rs.getBigDecimal("paid_amount"), currency),
                InvoiceStatus.CANCELLED.name().equals(rs.getString("status")),
                rs.getLong("version"));
    }
}

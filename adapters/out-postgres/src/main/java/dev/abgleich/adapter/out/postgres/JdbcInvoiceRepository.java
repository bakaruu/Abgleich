package dev.abgleich.adapter.out.postgres;

import dev.abgleich.application.port.out.DuplicateInvoiceException;
import dev.abgleich.application.port.out.InvoiceRepositoryPort;
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
import java.util.Currency;
import java.util.List;
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
                    .params(invoice.id(), invoice.number().value(), invoice.creditorAccount().value(),
                            invoice.debtorName(), invoice.amount().amount(), invoice.amount().currency().getCurrencyCode(),
                            References.text(invoice.reference()), invoice.dueDate(), invoice.paidAmount().amount(),
                            invoice.status().name(), invoice.version())
                    .update();
        } catch (DuplicateKeyException e) {
            throw new DuplicateInvoiceException("Invoice " + invoice.number() + " already exists", e);
        } catch (DataAccessException e) {
            throw new StorageException("The invoice could not be stored", e);
        }
    }

    @Override
    public List<Invoice> findOpen(Iban creditorAccount, Currency currency) {
        try {
            return client.sql("select " + COLUMNS + """
                             from invoice
                            where creditor_iban = ? and currency = ? and status in ('OPEN', 'PARTIALLY_PAID')
                            order by invoice_number
                            """)
                    .params(creditorAccount.value(), currency.getCurrencyCode())
                    .query((rs, row) -> invoice(rs))
                    .list();
        } catch (DataAccessException e) {
            throw new StorageException("Open invoices could not be read", e);
        }
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

package dev.abgleich.adapter.out.postgres;

import dev.abgleich.application.port.out.ReconciliationRepositoryPort;
import dev.abgleich.application.port.out.StaleDataException;
import dev.abgleich.application.port.out.StorageException;
import dev.abgleich.domain.account.Iban;
import dev.abgleich.domain.invoice.Invoice;
import dev.abgleich.domain.matching.Allocation;
import dev.abgleich.domain.matching.PaymentToMatch;
import dev.abgleich.domain.money.Direction;
import dev.abgleich.domain.money.Money;
import dev.abgleich.domain.reference.PaymentReference;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Currency;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Optimistic locking in SQL: every update says {@code where version = ?} and checks that one row
 * changed. A concurrent writer makes the count zero, the transaction rolls back and the caller gets
 * {@link StaleDataException} (B22). No row locks are held between reading and deciding.
 */
public final class JdbcReconciliationRepository implements ReconciliationRepositoryPort {

    private static final String PAYMENT_COLUMNS = """
            select id, account_iban, direction, amount, currency, reference, booking_date, version
              from bank_transaction
            """;

    private final JdbcClient client;
    private final TransactionTemplate transactions;

    public JdbcReconciliationRepository(DataSource dataSource, TransactionTemplate transactions) {
        this.client = JdbcClient.create(dataSource);
        this.transactions = transactions;
    }

    @Override
    public List<PaymentToMatch> findUnmatchedCredits(Iban account) {
        try {
            return client.sql(PAYMENT_COLUMNS + """
                            where account_iban = ? and status = 'UNMATCHED' and direction = 'CREDIT'
                            order by booking_date, dedup_key
                            """)
                    .param(account.value())
                    .query((rs, row) -> payment(rs))
                    .list();
        } catch (DataAccessException e) {
            throw new StorageException("Unmatched payments could not be read", e);
        }
    }

    @Override
    public Optional<PaymentToMatch> findUnmatchedCredit(UUID transactionId) {
        try {
            return client.sql(PAYMENT_COLUMNS + " where id = ? and status = 'UNMATCHED' and direction = 'CREDIT'")
                    .param(transactionId)
                    .query((rs, row) -> payment(rs))
                    .optional();
        } catch (DataAccessException e) {
            throw new StorageException("The payment could not be read", e);
        }
    }

    @Override
    public void recordConfirmed(PaymentToMatch payment, Allocation allocation, Invoice paidInvoice) {
        write(() -> {
            markPayment(payment, "MATCHED");
            int updated = client.sql("""
                            update invoice set paid_amount = ?, status = ?, version = version + 1
                             where id = ? and version = ?
                            """)
                    .params(paidInvoice.paidAmount().amount(), paidInvoice.status().name(), paidInvoice.id(),
                            paidInvoice.version())
                    .update();
            if (updated != 1) {
                throw new StaleDataException("Invoice " + paidInvoice.number() + " changed since it was read");
            }
            insert(allocation);
        });
    }

    @Override
    public void recordProposals(PaymentToMatch payment, List<Allocation> proposals) {
        write(() -> {
            markPayment(payment, "PROPOSED");
            proposals.forEach(this::insert);
        });
    }

    private void write(Runnable work) {
        try {
            transactions.executeWithoutResult(status -> work.run());
        } catch (DuplicateKeyException e) {
            // uq_allocation_active: someone already proposed or confirmed this pair (B33).
            throw new StaleDataException("An active allocation for this payment and invoice already exists", e);
        } catch (DataAccessException e) {
            throw new StorageException("The reconciliation could not be stored", e);
        }
    }

    private void markPayment(PaymentToMatch payment, String status) {
        int updated = client.sql("""
                        update bank_transaction set status = ?, version = version + 1
                         where id = ? and version = ? and status = 'UNMATCHED'
                        """)
                .params(status, payment.transactionId(), payment.version())
                .update();
        if (updated != 1) {
            throw new StaleDataException("The payment changed since it was read");
        }
    }

    private void insert(Allocation allocation) {
        client.sql("""
                        insert into allocation
                            (id, transaction_id, invoice_id, amount, rule, confidence, explanation, status,
                             decided_by, decided_at, created_at, version)
                        values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """)
                .params(allocation.id(), allocation.transactionId(), allocation.invoiceId(),
                        allocation.amount().amount(), allocation.rule().name(), allocation.confidence().value(),
                        allocation.explanation(), allocation.status().name(), allocation.decidedBy(),
                        allocation.decidedAt() == null ? null : OffsetDateTime.ofInstant(allocation.decidedAt(), ZoneOffset.UTC),
                        OffsetDateTime.ofInstant(allocation.createdAt(), ZoneOffset.UTC), allocation.version())
                .update();
    }

    private static PaymentToMatch payment(ResultSet rs) throws SQLException {
        return new PaymentToMatch(
                rs.getObject("id", UUID.class),
                Iban.of(rs.getString("account_iban")),
                Direction.valueOf(rs.getString("direction")),
                new Money(rs.getBigDecimal("amount"), Currency.getInstance(rs.getString("currency"))),
                PaymentReference.parse(rs.getString("reference")),
                rs.getObject("booking_date", LocalDate.class),
                rs.getLong("version"));
    }
}

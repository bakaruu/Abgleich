package dev.abgleich.adapter.out.postgres;

import dev.abgleich.application.StaleDataException;
import dev.abgleich.application.StorageException;
import dev.abgleich.application.reconciliation.port.out.ReconciliationRepositoryPort;
import dev.abgleich.domain.account.Iban;
import dev.abgleich.domain.invoice.Invoice;
import dev.abgleich.domain.invoice.InvoiceEvent;
import dev.abgleich.domain.matching.Allocation;
import dev.abgleich.domain.matching.AllocationStatus;
import dev.abgleich.domain.matching.PaymentToMatch;
import dev.abgleich.domain.money.Direction;
import dev.abgleich.domain.money.Money;
import dev.abgleich.domain.reference.PaymentReference;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.Currency;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
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

    static final String PAYMENT_COLUMNS = """
            select id, account_iban, direction, amount, currency, reference, remittance_text, counterparty_name,
                   end_to_end_id, charges, reversal, booking_date, version
              from bank_transaction
            """;

    private final JdbcClient client;
    private final TransactionTemplate transactions;

    public JdbcReconciliationRepository(DataSource dataSource, TransactionTemplate transactions) {
        this.client = JdbcClient.create(dataSource);
        this.transactions = transactions;
    }

    @Override
    public List<PaymentToMatch> findPendingCredits(Iban account) {
        return read("Unmatched payments could not be read", () -> client.sql(PAYMENT_COLUMNS + """
                        where account_iban = ? and status = 'UNMATCHED' and direction = 'CREDIT'
                        order by booking_date, dedup_key
                        """)
                .param(account.value())
                .query((rs, row) -> payment(rs))
                .list());
    }

    @Override
    public Optional<PaymentToMatch> findPendingCredit(UUID transactionId) {
        return read("The payment could not be read", () -> client.sql(PAYMENT_COLUMNS
                        + " where id = ? and status = 'UNMATCHED' and direction = 'CREDIT'")
                .param(transactionId)
                .query((rs, row) -> payment(rs))
                .optional());
    }

    @Override
    public Set<Set<UUID>> rejectedInvoiceSets(UUID transactionId) {
        return read("Rejected proposals could not be read", () -> {
            Map<UUID, Set<UUID>> groups = new HashMap<>();
            client.sql("select group_id, invoice_id from allocation where transaction_id = ? and status = 'REJECTED'")
                    .param(transactionId)
                    .query(rs -> {
                        groups.computeIfAbsent(rs.getObject("group_id", UUID.class), id -> new HashSet<>())
                                .add(rs.getObject("invoice_id", UUID.class));
                    });
            return new HashSet<>(groups.values());
        });
    }

    @Override
    public void recordConfirmed(PaymentToMatch payment, List<Allocation> confirmed, List<Invoice> settledInvoices,
            List<InvoiceEvent> events) {
        write(() -> {
            markPayment(payment.transactionId(), payment.version(), "UNMATCHED", "MATCHED");
            settledInvoices.forEach(invoice -> JdbcInvoiceRepository.update(client, invoice));
            confirmed.forEach(allocation -> AllocationRows.insert(client, allocation));
            events.forEach(event -> OutboxRows.insert(client, event));
        });
    }

    @Override
    public void recordProposals(PaymentToMatch payment, List<Allocation> proposals) {
        write(() -> {
            markPayment(payment.transactionId(), payment.version(), "UNMATCHED", "PROPOSED");
            proposals.forEach(allocation -> AllocationRows.insert(client, allocation));
        });
    }

    @Override
    public List<PaymentToMatch> findPendingReversals(Iban account) {
        return read("Reversals could not be read", () -> client.sql(PAYMENT_COLUMNS + """
                        where account_iban = ? and status = 'UNMATCHED' and direction = 'DEBIT' and reversal
                        order by booking_date, dedup_key
                        """)
                .param(account.value())
                .query((rs, row) -> payment(rs))
                .list());
    }

    @Override
    public Optional<ReversedPayment> findReversedCredit(PaymentToMatch reversal) {
        String reference = References.text(reversal.reference());
        if (reversal.endToEndId() == null && reference == null) {
            return Optional.empty();
        }
        return read("The reversed payment could not be read", () -> {
            List<Map<String, Object>> credits = client.sql("""
                            select id, version from bank_transaction
                             where account_iban = ? and direction = 'CREDIT' and status = 'MATCHED'
                               and amount = ? and currency = ? and booking_date <= ?
                               and ((end_to_end_id is not null and end_to_end_id = ?)
                                 or (reference is not null and reference = ?))
                             limit 2
                            """)
                    .params(reversal.account().value(), reversal.amount().amount(),
                            reversal.amount().currency().getCurrencyCode(), reversal.bookingDate(),
                            reversal.endToEndId(), reference)
                    .query().listOfRows();
            if (credits.size() != 1) {
                return Optional.empty();
            }
            UUID transactionId = (UUID) credits.getFirst().get("id");
            List<Allocation> allocations = client.sql("select " + AllocationRows.COLUMNS + """
                             from allocation a join bank_transaction t on t.id = a.transaction_id
                            where a.transaction_id = ? and a.status = 'CONFIRMED'
                            """)
                    .param(transactionId)
                    .query((rs, row) -> AllocationRows.map(rs))
                    .list();
            List<Invoice> invoices = JdbcInvoiceRepository.findAll(client,
                    allocations.stream().map(Allocation::invoiceId).toList());
            return Optional.of(new ReversedPayment(transactionId, (Long) credits.getFirst().get("version"),
                    allocations, invoices));
        });
    }

    @Override
    public void recordReversal(PaymentToMatch reversal, ReversedPayment original, List<Allocation> reversedAllocations,
            List<Invoice> reopenedInvoices, List<InvoiceEvent> events) {
        write(() -> {
            markPayment(reversal.transactionId(), reversal.version(), "UNMATCHED", "MATCHED");
            markPayment(original.transactionId(), original.version(), "MATCHED", "REVERSED");
            reversedAllocations.forEach(allocation ->
                    AllocationRows.decide(client, allocation, AllocationStatus.CONFIRMED));
            reopenedInvoices.forEach(invoice -> JdbcInvoiceRepository.update(client, invoice));
            events.forEach(event -> OutboxRows.insert(client, event));
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

    private void markPayment(UUID transactionId, long version, String from, String to) {
        int updated = client.sql("""
                        update bank_transaction set status = ?, version = version + 1
                         where id = ? and version = ? and status = ?
                        """)
                .params(to, transactionId, version, from)
                .update();
        if (updated != 1) {
            throw new StaleDataException("The payment changed since it was read");
        }
    }

    private static <T> T read(String failure, java.util.function.Supplier<T> query) {
        try {
            return query.get();
        } catch (DataAccessException e) {
            throw new StorageException(failure, e);
        }
    }

    static PaymentToMatch payment(ResultSet rs) throws SQLException {
        Currency currency = Currency.getInstance(rs.getString("currency"));
        BigDecimal charges = rs.getBigDecimal("charges");
        return new PaymentToMatch(
                rs.getObject("id", UUID.class),
                Iban.of(rs.getString("account_iban")),
                Direction.valueOf(rs.getString("direction")),
                new Money(rs.getBigDecimal("amount"), currency),
                PaymentReference.parse(rs.getString("reference")),
                rs.getString("remittance_text"),
                rs.getString("counterparty_name"),
                rs.getString("end_to_end_id"),
                charges == null ? null : new Money(charges, currency),
                rs.getBoolean("reversal"),
                rs.getObject("booking_date", LocalDate.class),
                rs.getLong("version"));
    }
}

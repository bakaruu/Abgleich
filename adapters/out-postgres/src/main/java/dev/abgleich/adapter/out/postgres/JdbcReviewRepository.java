package dev.abgleich.adapter.out.postgres;

import dev.abgleich.application.port.out.ReviewRepositoryPort;
import dev.abgleich.application.port.out.StaleDataException;
import dev.abgleich.application.port.out.StorageException;
import dev.abgleich.domain.invoice.Invoice;
import dev.abgleich.domain.matching.Allocation;
import dev.abgleich.domain.matching.AllocationStatus;
import dev.abgleich.domain.money.Money;
import dev.abgleich.domain.statement.TransactionStatus;
import java.util.Currency;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Stores review decisions. The payment's version is the token the reviewer saw (B34); every
 * allocation must still be proposed (B33). Either condition failing rolls the whole decision back.
 */
public final class JdbcReviewRepository implements ReviewRepositoryPort {

    private final JdbcClient client;
    private final TransactionTemplate transactions;

    public JdbcReviewRepository(DataSource dataSource, TransactionTemplate transactions) {
        this.client = JdbcClient.create(dataSource);
        this.transactions = transactions;
    }

    @Override
    public Optional<ProposalGroup> findGroup(UUID groupId) {
        try {
            List<Allocation> allocations = allocations("a.group_id = ?", groupId);
            if (allocations.isEmpty()) {
                return Optional.empty();
            }
            UUID transactionId = allocations.getFirst().transactionId();
            Map<String, Object> transaction = client.sql(
                            "select status, amount, currency, version from bank_transaction where id = ?")
                    .param(transactionId)
                    .query().singleRow();
            List<Invoice> invoices = JdbcInvoiceRepository.findAll(client,
                    allocations.stream().map(Allocation::invoiceId).toList());
            List<Allocation> others = allocations("a.transaction_id = ? and a.status = 'PROPOSED' and a.group_id <> ?",
                    transactionId, groupId);
            return Optional.of(new ProposalGroup(groupId, transactionId, (Long) transaction.get("version"),
                    TransactionStatus.valueOf((String) transaction.get("status")),
                    new Money((java.math.BigDecimal) transaction.get("amount"),
                            Currency.getInstance(((String) transaction.get("currency")).strip())),
                    allocations, invoices, others));
        } catch (DataAccessException e) {
            throw new StorageException("The proposal could not be read", e);
        }
    }

    @Override
    public void recordConfirmation(ProposalGroup group, List<Allocation> confirmed, List<Invoice> settledInvoices,
            List<Allocation> superseded) {
        write(() -> {
            markPayment(group, "MATCHED");
            confirmed.forEach(allocation -> AllocationRows.decide(client, allocation, AllocationStatus.PROPOSED));
            superseded.forEach(allocation -> AllocationRows.decide(client, allocation, AllocationStatus.PROPOSED));
            settledInvoices.forEach(invoice -> JdbcInvoiceRepository.update(client, invoice));
        });
    }

    @Override
    public void recordRejection(ProposalGroup group, List<Allocation> rejected) {
        write(() -> {
            rejected.forEach(allocation -> AllocationRows.decide(client, allocation, AllocationStatus.PROPOSED));
            markPayment(group, group.otherProposals().isEmpty() ? "UNMATCHED" : "PROPOSED");
        });
    }

    private List<Allocation> allocations(String condition, Object... params) {
        return client.sql("select " + AllocationRows.COLUMNS
                        + " from allocation a join bank_transaction t on t.id = a.transaction_id where " + condition
                        + " order by a.created_at, a.id")
                .params(params)
                .query((rs, row) -> AllocationRows.map(rs))
                .list();
    }

    private void markPayment(ProposalGroup group, String status) {
        int updated = client.sql("""
                        update bank_transaction set status = ?, version = version + 1
                         where id = ? and version = ? and status = 'PROPOSED'
                        """)
                .params(status, group.transactionId(), group.transactionVersion())
                .update();
        if (updated != 1) {
            throw new StaleDataException("The payment was decided by someone else");
        }
    }

    private void write(Runnable work) {
        try {
            transactions.executeWithoutResult(status -> work.run());
        } catch (DataAccessException e) {
            throw new StorageException("The decision could not be stored", e);
        }
    }
}

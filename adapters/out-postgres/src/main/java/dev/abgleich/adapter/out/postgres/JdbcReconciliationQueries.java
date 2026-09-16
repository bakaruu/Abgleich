package dev.abgleich.adapter.out.postgres;

import dev.abgleich.application.port.in.InvoiceQuery.AllocationView;
import dev.abgleich.application.port.in.InvoiceQuery.InvoiceDetail;
import dev.abgleich.application.port.in.InvoiceQuery.InvoiceView;
import dev.abgleich.application.port.in.ReviewQueueQuery.Proposal;
import dev.abgleich.application.port.in.ReviewQueueQuery.ReviewItem;
import dev.abgleich.application.port.in.ReviewQueueQuery.Share;
import dev.abgleich.application.port.out.ReconciliationQueriesPort;
import dev.abgleich.application.port.out.StorageException;
import dev.abgleich.domain.account.Iban;
import dev.abgleich.domain.invoice.InvoiceStatus;
import dev.abgleich.domain.matching.AllocationStatus;
import dev.abgleich.domain.matching.Confidence;
import dev.abgleich.domain.matching.MatchRule;
import dev.abgleich.domain.money.Money;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Currency;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Read models of the review queue and the invoices. */
public final class JdbcReconciliationQueries implements ReconciliationQueriesPort {

    private final JdbcClient client;

    public JdbcReconciliationQueries(DataSource dataSource) {
        this.client = JdbcClient.create(dataSource);
    }

    @Override
    public List<ReviewItem> pendingReview(int limit) {
        try {
            List<PendingPayment> payments = client.sql("""
                            select id, version, account_iban, booking_date, amount, currency, counterparty_name,
                                   remittance_text, reference
                              from bank_transaction
                             where status = 'PROPOSED'
                             order by booking_date, id
                             limit ?
                            """)
                    .param(limit)
                    .query((rs, row) -> new PendingPayment(rs.getObject("id", UUID.class), rs.getLong("version"),
                            Iban.of(rs.getString("account_iban")), rs.getObject("booking_date", LocalDate.class),
                            money(rs, "amount"), rs.getString("counterparty_name"), rs.getString("remittance_text"),
                            rs.getString("reference")))
                    .list();
            if (payments.isEmpty()) {
                return List.of();
            }
            Map<UUID, Map<UUID, ProposalRows>> proposals = proposals(payments.stream().map(PendingPayment::id).toList());
            return payments.stream()
                    .map(payment -> new ReviewItem(payment.id(), payment.version(), payment.account(),
                            payment.bookingDate(), payment.amount(), payment.counterpartyName(),
                            payment.remittanceText(), payment.reference(),
                            proposals.getOrDefault(payment.id(), Map.of()).values().stream()
                                    .map(ProposalRows::toProposal)
                                    .sorted(Comparator.comparing((Proposal p) -> p.confidence().value()).reversed()
                                            .thenComparing(p -> p.shares().stream().map(Share::dueDate)
                                                    .min(Comparator.naturalOrder()).orElseThrow())
                                            .thenComparing(p -> p.shares().getFirst().invoiceNumber()))
                                    .toList()))
                    .toList();
        } catch (DataAccessException e) {
            throw new StorageException("The review queue could not be read", e);
        }
    }

    @Override
    public long pendingReviewCount() {
        try {
            return client.sql("select count(*) from bank_transaction where status = 'PROPOSED'").query(Long.class).single();
        } catch (DataAccessException e) {
            throw new StorageException("The review queue could not be counted", e);
        }
    }

    @Override
    public List<InvoiceView> invoices(InvoiceStatus status, int limit) {
        try {
            return client.sql("select " + JdbcInvoiceRepository.COLUMNS + " from invoice"
                            + " where (cast(:status as varchar) is null or status = :status)"
                            + " order by invoice_number limit :limit")
                    .param("status", status == null ? null : status.name())
                    .param("limit", limit)
                    .query((rs, row) -> invoiceView(rs))
                    .list();
        } catch (DataAccessException e) {
            throw new StorageException("Invoices could not be read", e);
        }
    }

    @Override
    public Optional<InvoiceDetail> invoiceDetail(UUID invoiceId) {
        try {
            return client.sql("select " + JdbcInvoiceRepository.COLUMNS + " from invoice where id = ?")
                    .param(invoiceId)
                    .query((rs, row) -> invoiceView(rs))
                    .optional()
                    .map(invoice -> new InvoiceDetail(invoice, client.sql("""
                                    select a.id, a.transaction_id, t.booking_date, t.amount as transaction_amount,
                                           t.counterparty_name, a.amount, a.charges_written_off, t.currency, a.rule,
                                           a.status, a.explanation, a.decided_by, a.decided_at, a.decision_note
                                      from allocation a join bank_transaction t on t.id = a.transaction_id
                                     where a.invoice_id = ?
                                     order by a.created_at, a.id
                                    """)
                            .param(invoiceId)
                            .query((rs, row) -> {
                                OffsetDateTime decidedAt = rs.getObject("decided_at", OffsetDateTime.class);
                                return new AllocationView(rs.getObject("id", UUID.class),
                                        rs.getObject("transaction_id", UUID.class),
                                        rs.getObject("booking_date", LocalDate.class), money(rs, "transaction_amount"),
                                        rs.getString("counterparty_name"), money(rs, "amount"),
                                        money(rs, "charges_written_off"), MatchRule.valueOf(rs.getString("rule")),
                                        AllocationStatus.valueOf(rs.getString("status")), rs.getString("explanation"),
                                        rs.getString("decided_by"), decidedAt == null ? null : decidedAt.toInstant(),
                                        rs.getString("decision_note"));
                            })
                            .list()));
        } catch (DataAccessException e) {
            throw new StorageException("The invoice could not be read", e);
        }
    }

    private Map<UUID, Map<UUID, ProposalRows>> proposals(List<UUID> transactionIds) {
        Map<UUID, Map<UUID, ProposalRows>> byTransaction = new LinkedHashMap<>();
        client.sql("""
                        select a.transaction_id, a.group_id, a.rule, a.confidence, a.explanation, a.amount,
                               a.charges_written_off, t.currency, i.id as invoice_id, i.invoice_number, i.debtor_name,
                               i.amount as invoice_amount, i.paid_amount, i.status as invoice_status, i.due_date
                          from allocation a
                          join bank_transaction t on t.id = a.transaction_id
                          join invoice i on i.id = a.invoice_id
                         where a.status = 'PROPOSED' and a.transaction_id in (:ids)
                         order by i.invoice_number
                        """)
                .param("ids", transactionIds)
                .query(rs -> {
                    ProposalRows group = byTransaction
                            .computeIfAbsent(rs.getObject("transaction_id", UUID.class), id -> new LinkedHashMap<>())
                            .computeIfAbsent(rs.getObject("group_id", UUID.class), id -> {
                                try {
                                    return new ProposalRows(id, MatchRule.valueOf(rs.getString("rule")),
                                            new Confidence(rs.getBigDecimal("confidence")), rs.getString("explanation"));
                                } catch (SQLException e) {
                                    throw new IllegalStateException(e);
                                }
                            });
                    Money invoiceAmount = money(rs, "invoice_amount");
                    Money paid = money(rs, "paid_amount");
                    group.shares.add(new Share(rs.getObject("invoice_id", UUID.class), rs.getString("invoice_number"),
                            rs.getString("debtor_name"), invoiceAmount, invoiceAmount.subtract(paid), money(rs, "amount"),
                            money(rs, "charges_written_off"), InvoiceStatus.valueOf(rs.getString("invoice_status")),
                            rs.getObject("due_date", LocalDate.class)));
                });
        return byTransaction;
    }

    private static InvoiceView invoiceView(ResultSet rs) throws SQLException {
        return new InvoiceView(rs.getObject("id", UUID.class), rs.getString("invoice_number"),
                Iban.of(rs.getString("creditor_iban")), rs.getString("debtor_name"), money(rs, "amount"),
                money(rs, "paid_amount"), InvoiceStatus.valueOf(rs.getString("status")),
                rs.getObject("due_date", LocalDate.class), rs.getString("reference"), rs.getLong("version"));
    }

    private static Money money(ResultSet rs, String column) throws SQLException {
        return new Money(rs.getBigDecimal(column), Currency.getInstance(rs.getString("currency").strip()));
    }

    private record PendingPayment(UUID id, long version, Iban account, LocalDate bookingDate, Money amount,
            String counterpartyName, String remittanceText, String reference) {
    }

    private static final class ProposalRows {
        private final UUID groupId;
        private final MatchRule rule;
        private final Confidence confidence;
        private final String explanation;
        private final List<Share> shares = new ArrayList<>();

        ProposalRows(UUID groupId, MatchRule rule, Confidence confidence, String explanation) {
            this.groupId = groupId;
            this.rule = rule;
            this.confidence = confidence;
            this.explanation = explanation;
        }

        Proposal toProposal() {
            return new Proposal(groupId, rule, confidence, explanation, shares);
        }
    }
}

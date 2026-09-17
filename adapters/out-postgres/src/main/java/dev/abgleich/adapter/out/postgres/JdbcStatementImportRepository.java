package dev.abgleich.adapter.out.postgres;

import dev.abgleich.application.StorageException;
import dev.abgleich.application.statement.EnrichedNotification;
import dev.abgleich.application.statement.ImportedStatement;
import dev.abgleich.application.statement.port.out.DuplicateImportException;
import dev.abgleich.application.statement.port.out.NewStatementImport;
import dev.abgleich.application.statement.port.out.StatementImportRepositoryPort;
import dev.abgleich.domain.account.Iban;
import dev.abgleich.domain.money.Money;
import dev.abgleich.domain.statement.Balance;
import dev.abgleich.domain.statement.DeduplicationKey;
import dev.abgleich.domain.statement.Notification;
import dev.abgleich.domain.statement.Statement;
import dev.abgleich.domain.statement.StatementEntry;
import dev.abgleich.domain.statement.TransactionDetail;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Currency;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import javax.sql.DataSource;
import org.postgresql.util.PSQLException;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Stores imports with plain SQL. Idempotency lives in the schema: duplicate files are refused by
 * unique constraints (B21) and already known transactions are skipped with
 * {@code on conflict do nothing} (B12, B16), so concurrent imports cannot slip past a check.
 */
public final class JdbcStatementImportRepository implements StatementImportRepositoryPort {

    private static final Set<String> DUPLICATE_IMPORT_CONSTRAINTS = Set.of("uq_import_file", "uq_import_message");
    private static final int BATCH_SIZE = 1_000;

    private static final String INSERT_IMPORT = """
            insert into statement_import
                (id, source, format, file_sha256, message_id, account_iban, currency,
                 opening_bal, opening_date, closing_bal, closing_date, status, received_at)
            values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'STORED', ?)
            """;

    private static final String INSERT_TRANSACTION = """
            insert into bank_transaction
                (id, import_id, account_iban, dedup_key, booking_date, value_date, direction, amount, currency,
                 reference, remittance_text, bank_reference, end_to_end_id, counterparty_name, reversal, charges, status)
            values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'UNMATCHED')
            on conflict (account_iban, dedup_key) do nothing
            """;

    private final JdbcTemplate jdbc;
    private final JdbcClient client;
    private final TransactionTemplate transactions;

    public JdbcStatementImportRepository(DataSource dataSource, TransactionTemplate transactions) {
        this.jdbc = new JdbcTemplate(dataSource);
        this.client = JdbcClient.create(dataSource);
        this.transactions = Objects.requireNonNull(transactions, "transactions");
    }

    @Override
    public List<ImportedStatement> store(List<NewStatementImport> imports) {
        try {
            return transactions.execute(status -> imports.stream().map(this::storeOne).toList());
        } catch (DuplicateKeyException e) {
            String constraint = constraintName(e);
            if (DUPLICATE_IMPORT_CONSTRAINTS.contains(constraint)) {
                throw new DuplicateImportException("Statement already imported (" + constraint + ")", e);
            }
            throw new StorageException("Unexpected unique constraint violation: " + constraint, e);
        } catch (DataAccessException e) {
            throw new StorageException("The import could not be stored", e);
        }
    }

    @Override
    public List<ImportedStatement> findPrevious(String fileSha256, String messageId, List<Iban> accounts) {
        try {
            return client.sql("""
                            select i.id, i.account_iban, i.currency, i.opening_bal, i.opening_date,
                                   i.closing_bal, i.closing_date,
                                   (select count(*) from bank_transaction t where t.import_id = i.id) as stored
                            from statement_import i
                            where i.file_sha256 = :sha
                               or (i.message_id = :messageId and i.account_iban in (:accounts))
                            order by i.received_at, i.account_iban
                            """)
                    .param("sha", fileSha256)
                    .param("messageId", messageId)
                    .param("accounts", accounts.isEmpty() ? List.of("") : accounts.stream().map(Iban::value).toList())
                    .query((rs, row) -> previousImport(rs))
                    .list();
        } catch (DataAccessException e) {
            throw new StorageException("Previous imports could not be read", e);
        }
    }

    /**
     * Fills only empty columns and never creates rows (B12). An entry stored without details by the
     * statement but notified with several transactions has no row per transaction to enrich: those
     * transactions are counted as unknown.
     */
    @Override
    public List<EnrichedNotification> enrich(List<Notification> notifications) {
        try {
            return transactions.execute(status -> notifications.stream().map(this::enrichOne).toList());
        } catch (DataAccessException e) {
            throw new StorageException("The notification could not be stored", e);
        }
    }

    private EnrichedNotification enrichOne(Notification notification) {
        int enriched = 0;
        int complete = 0;
        int unknown = 0;
        List<DeduplicationKey> keys = notification.deduplicationKeys();
        for (int i = 0; i < notification.entries().size(); i++) {
            StatementEntry entry = notification.entries().get(i);
            int count = entry.details().size();
            for (int n = 1; n <= Math.max(count, 1); n++) {
                DeduplicationKey key = count == 0 ? keys.get(i) : keys.get(i).forTransaction(n, count);
                TransactionDetail detail = count == 0 ? null : entry.details().get(n - 1);
                if (detail != null && fillEmptyColumns(notification, key, detail)) {
                    enriched++;
                } else if (exists(notification, key)) {
                    complete++;
                } else {
                    unknown++;
                }
            }
        }
        return new EnrichedNotification(notification.account(), enriched, complete, unknown);
    }

    private boolean fillEmptyColumns(Notification notification, DeduplicationKey key, TransactionDetail detail) {
        String reference = References.text(detail.reference());
        String remittance = fit(detail.remittanceText(), 500);
        String counterparty = fit(detail.counterpartyName(), 140);
        String endToEnd = fit(detail.endToEndId(), 35);
        java.math.BigDecimal charges = detail.charges() == null ? null : detail.charges().amount();
        return client.sql("""
                        update bank_transaction
                           set reference = coalesce(reference, :reference),
                               remittance_text = coalesce(remittance_text, :remittance),
                               counterparty_name = coalesce(counterparty_name, :counterparty),
                               end_to_end_id = coalesce(end_to_end_id, :endToEnd),
                               charges = coalesce(charges, :charges)
                         where account_iban = :account and dedup_key = :key
                           and ((reference is null and cast(:reference as varchar) is not null)
                             or (remittance_text is null and cast(:remittance as varchar) is not null)
                             or (counterparty_name is null and cast(:counterparty as varchar) is not null)
                             or (end_to_end_id is null and cast(:endToEnd as varchar) is not null)
                             or (charges is null and cast(:charges as numeric) is not null))
                        """)
                .param("reference", reference)
                .param("remittance", remittance)
                .param("counterparty", counterparty)
                .param("endToEnd", endToEnd)
                .param("charges", charges)
                .param("account", notification.account().value())
                .param("key", key.value())
                .update() == 1;
    }

    private boolean exists(Notification notification, DeduplicationKey key) {
        return client.sql("select count(*) from bank_transaction where account_iban = ? and dedup_key = ?")
                .params(notification.account().value(), key.value())
                .query(Integer.class)
                .single() > 0;
    }

    private ImportedStatement storeOne(NewStatementImport newImport) {
        Statement statement = newImport.statement();
        jdbc.update(INSERT_IMPORT,
                newImport.id(), newImport.source().name(), newImport.format().name(), newImport.fileSha256(),
                newImport.messageId(), statement.account().value(), statement.currency().getCurrencyCode(),
                statement.openingBalance().amount().amount(), statement.openingBalance().date(),
                statement.closingBalance().amount().amount(), statement.closingBalance().date(),
                OffsetDateTime.ofInstant(newImport.receivedAt(), ZoneOffset.UTC));

        List<Object[]> rows = transactionRows(newImport.id(), statement);
        for (int from = 0; from < rows.size(); from += BATCH_SIZE) {
            jdbc.batchUpdate(INSERT_TRANSACTION, rows.subList(from, Math.min(from + BATCH_SIZE, rows.size())));
        }
        // Counting is independent of the driver's batch result mode (rewritten batches report no counts).
        int stored = storedTransactions(newImport.id());
        return new ImportedStatement(newImport.id(), statement.account(), statement.openingBalance(),
                statement.closingBalance(), stored, rows.size() - stored);
    }

    private static List<Object[]> transactionRows(UUID importId, Statement statement) {
        List<Object[]> rows = new ArrayList<>();
        List<DeduplicationKey> keys = statement.deduplicationKeys();
        for (int i = 0; i < statement.entries().size(); i++) {
            StatementEntry entry = statement.entries().get(i);
            if (entry.details().isEmpty()) {
                rows.add(row(importId, statement, entry, keys.get(i), entry.amount(), null));
                continue;
            }
            int count = entry.details().size();
            for (int n = 1; n <= count; n++) {
                TransactionDetail detail = entry.details().get(n - 1);
                rows.add(row(importId, statement, entry, keys.get(i).forTransaction(n, count), detail.amount(), detail));
            }
        }
        return rows;
    }

    private static Object[] row(UUID importId, Statement statement, StatementEntry entry, DeduplicationKey key,
            Money amount, TransactionDetail detail) {
        String bankReference = detail != null && detail.bankReference() != null
                ? detail.bankReference()
                : entry.bankReference();
        return new Object[] {
            UUID.randomUUID(), importId, statement.account().value(), key.value(),
            entry.bookingDate(), entry.valueDate(), entry.direction().name(),
            amount.amount(), amount.currency().getCurrencyCode(),
            detail == null ? null : References.text(detail.reference()),
            detail == null ? null : fit(detail.remittanceText(), 500),
            fit(bankReference, 35),
            detail == null ? null : fit(detail.endToEndId(), 35),
            detail == null ? null : fit(detail.counterpartyName(), 140),
            entry.reversal(),
            detail == null || detail.charges() == null ? null : detail.charges().amount()
        };
    }

    /** Bank files may hold longer texts than the columns; they are only used for display and matching. */
    private static String fit(String text, int maxLength) {
        return text == null || text.length() <= maxLength ? text : text.substring(0, maxLength);
    }

    private int storedTransactions(UUID importId) {
        return client.sql("select count(*) from bank_transaction where import_id = ?")
                .param(importId)
                .query(Integer.class)
                .single();
    }

    private static ImportedStatement previousImport(ResultSet rs) throws SQLException {
        Currency currency = Currency.getInstance(rs.getString("currency"));
        return new ImportedStatement(
                rs.getObject("id", UUID.class),
                Iban.of(rs.getString("account_iban")),
                new Balance(new Money(rs.getBigDecimal("opening_bal"), currency), rs.getObject("opening_date", LocalDate.class)),
                new Balance(new Money(rs.getBigDecimal("closing_bal"), currency), rs.getObject("closing_date", LocalDate.class)),
                rs.getInt("stored"),
                0);
    }

    private static String constraintName(Throwable error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof PSQLException psql && psql.getServerErrorMessage() != null) {
                return psql.getServerErrorMessage().getConstraint();
            }
        }
        return "unknown";
    }
}

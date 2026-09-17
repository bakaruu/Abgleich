package dev.abgleich.application.statement.port.out;

import dev.abgleich.application.statement.EnrichedNotification;
import dev.abgleich.application.statement.ImportedStatement;
import dev.abgleich.domain.account.Iban;
import dev.abgleich.domain.statement.Notification;
import java.util.List;

public interface StatementImportRepositoryPort {

    /**
     * Stores every statement of one file and its transactions in a single short transaction: all
     * or nothing. Transactions whose deduplication key is already stored for the account are
     * skipped and counted as known (B12, B16).
     *
     * @throws DuplicateImportException if the database already holds this file or message (B21)
     */
    List<ImportedStatement> store(List<NewStatementImport> imports);

    /** Earlier imports of the same file, or of the same camt message for one of the accounts. */
    List<ImportedStatement> findPrevious(String fileSha256, String messageId, List<Iban> accounts);

    /**
     * Adds details from a camt.054 to transactions already stored by a statement: only empty fields are
     * filled, nothing is overwritten and no transaction is created (B12). Entries are found by the same
     * deduplication key a statement produces.
     */
    List<EnrichedNotification> enrich(List<Notification> notifications);
}

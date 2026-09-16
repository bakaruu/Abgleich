package dev.abgleich.application.port.out;

import dev.abgleich.application.port.in.ImportedStatement;
import dev.abgleich.domain.account.Iban;
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
}

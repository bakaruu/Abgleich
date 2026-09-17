package dev.abgleich.application.statement.port.in;

import dev.abgleich.domain.statement.InvalidStatementException;

/**
 * Imports a bank statement file: detects its format, validates it and stores its transactions as
 * unmatched. Importing the same file again changes nothing (B21). Matching is a separate step that
 * runs after the import has committed (B26).
 */
public interface ImportStatementUseCase {

    /**
     * Files larger than this are rejected (B42). Part of the port, so every channel (web upload, SFTP, bank
     * download) can refuse a file early without knowing the service.
     */
    long MAX_FILE_BYTES = 20L * 1024 * 1024;

    /** @throws InvalidStatementException if the file is rejected; nothing is stored in that case */
    ImportResult importStatement(ImportStatementCommand command);
}

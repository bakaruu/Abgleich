package dev.abgleich.application.port.in;

import dev.abgleich.domain.statement.InvalidStatementException;

/**
 * Imports a bank statement file: detects its format, validates it and stores its transactions as
 * unmatched. Importing the same file again changes nothing (B21). Matching is a separate step that
 * runs after the import has committed (B26).
 */
public interface ImportStatementUseCase {

    /** @throws InvalidStatementException if the file is rejected; nothing is stored in that case */
    ImportResult importStatement(ImportStatementCommand command);
}

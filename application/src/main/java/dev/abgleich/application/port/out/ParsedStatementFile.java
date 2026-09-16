package dev.abgleich.application.port.out;

import dev.abgleich.domain.statement.Statement;
import java.util.List;
import java.util.Objects;

/**
 * Everything read from one file. A file can hold statements for several accounts
 * (several {@code Stmt} in camt, several accounts in Norma 43).
 *
 * @param messageId the file-level message id ({@code MsgId} in camt), or {@code null} if the format has none
 */
public record ParsedStatementFile(StatementFormat format, String messageId, List<Statement> statements) {

    public ParsedStatementFile {
        Objects.requireNonNull(format, "format");
        Objects.requireNonNull(statements, "statements");
        if (messageId != null && messageId.isBlank()) {
            messageId = null;
        }
        if (statements.isEmpty()) {
            throw new IllegalArgumentException("A statement file must contain at least one statement");
        }
        statements = List.copyOf(statements);
    }
}

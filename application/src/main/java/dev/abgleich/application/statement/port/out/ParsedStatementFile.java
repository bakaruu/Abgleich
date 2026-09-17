package dev.abgleich.application.statement.port.out;

import dev.abgleich.application.statement.StatementFormat;
import dev.abgleich.domain.statement.Notification;
import dev.abgleich.domain.statement.Statement;
import java.util.List;
import java.util.Objects;

/**
 * Everything read from one file: statements with balances (camt.053, Norma 43, CSV) or intraday
 * notifications without balances (camt.054), never both. A file can hold several accounts.
 *
 * @param messageId the file-level message id ({@code MsgId} in camt), or {@code null} if the format has none
 */
public record ParsedStatementFile(
        StatementFormat format,
        String messageId,
        List<Statement> statements,
        List<Notification> notifications) {

    public ParsedStatementFile {
        Objects.requireNonNull(format, "format");
        Objects.requireNonNull(statements, "statements");
        Objects.requireNonNull(notifications, "notifications");
        if (messageId != null && messageId.isBlank()) {
            messageId = null;
        }
        if (statements.isEmpty() == notifications.isEmpty()) {
            throw new IllegalArgumentException("A file holds either statements or notifications");
        }
        statements = List.copyOf(statements);
        notifications = List.copyOf(notifications);
    }

    public ParsedStatementFile(StatementFormat format, String messageId, List<Statement> statements) {
        this(format, messageId, statements, List.of());
    }

    public static ParsedStatementFile ofNotifications(StatementFormat format, String messageId,
            List<Notification> notifications) {
        return new ParsedStatementFile(format, messageId, List.of(), notifications);
    }

    public boolean isNotification() {
        return !notifications.isEmpty();
    }
}

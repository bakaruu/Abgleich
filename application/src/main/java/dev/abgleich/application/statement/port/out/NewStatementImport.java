package dev.abgleich.application.statement.port.out;

import dev.abgleich.application.statement.ImportSource;
import dev.abgleich.application.statement.StatementFormat;
import dev.abgleich.domain.statement.Statement;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/** One validated statement of a file, ready to be stored with all its transactions. */
public record NewStatementImport(
        UUID id,
        ImportSource source,
        StatementFormat format,
        String fileSha256,
        String messageId,
        Statement statement,
        Instant receivedAt) {

    private static final Pattern SHA256_HEX = Pattern.compile("[0-9a-f]{64}");

    public NewStatementImport {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(format, "format");
        Objects.requireNonNull(fileSha256, "fileSha256");
        Objects.requireNonNull(statement, "statement");
        Objects.requireNonNull(receivedAt, "receivedAt");
        if (!SHA256_HEX.matcher(fileSha256).matches()) {
            throw new IllegalArgumentException("fileSha256 must be 64 lower-case hex characters");
        }
    }
}

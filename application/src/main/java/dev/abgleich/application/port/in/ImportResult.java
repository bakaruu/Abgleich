package dev.abgleich.application.port.in;

import dev.abgleich.application.port.out.StatementFormat;
import java.util.List;
import java.util.Objects;

/**
 * @param outcome {@link Outcome#ALREADY_IMPORTED} when the file, or a camt message with the same id,
 *     was imported before; {@code statements} then describes that earlier import
 */
public record ImportResult(Outcome outcome, StatementFormat format, List<ImportedStatement> statements) {

    public enum Outcome {
        IMPORTED,
        ALREADY_IMPORTED
    }

    public ImportResult {
        Objects.requireNonNull(outcome, "outcome");
        Objects.requireNonNull(format, "format");
        statements = List.copyOf(statements);
    }
}

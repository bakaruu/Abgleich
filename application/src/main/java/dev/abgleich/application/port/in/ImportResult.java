package dev.abgleich.application.port.in;

import dev.abgleich.application.port.out.StatementFormat;
import java.util.List;
import java.util.Objects;

/**
 * @param outcome {@link Outcome#ALREADY_IMPORTED} when the file, or a camt message with the same id,
 *     was imported before; {@code statements} then describes that earlier import. {@link Outcome#ENRICHED}
 *     for a notification file, which adds details to stored transactions and never creates any (B12).
 */
public record ImportResult(
        Outcome outcome,
        StatementFormat format,
        List<ImportedStatement> statements,
        List<EnrichedNotification> enriched) {

    public enum Outcome {
        IMPORTED,
        ALREADY_IMPORTED,
        ENRICHED
    }

    public ImportResult {
        Objects.requireNonNull(outcome, "outcome");
        Objects.requireNonNull(format, "format");
        statements = List.copyOf(statements);
        enriched = List.copyOf(enriched);
    }

    public ImportResult(Outcome outcome, StatementFormat format, List<ImportedStatement> statements) {
        this(outcome, format, statements, List.of());
    }
}

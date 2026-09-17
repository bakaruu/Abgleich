package dev.abgleich.application.statement.port.in;

import dev.abgleich.application.statement.StatementReport;
import java.util.Optional;
import java.util.UUID;

/** Read side for the upload result screen and {@code GET /api/v1/statements/{id}}. */
public interface StatementReportQuery {

    Optional<StatementReport> report(UUID importId);
}

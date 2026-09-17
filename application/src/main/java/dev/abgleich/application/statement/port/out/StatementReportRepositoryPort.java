package dev.abgleich.application.statement.port.out;

import dev.abgleich.application.statement.StatementReport;
import java.util.Optional;
import java.util.UUID;

public interface StatementReportRepositoryPort {

    Optional<StatementReport> findReport(UUID importId);
}

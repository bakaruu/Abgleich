package dev.abgleich.application.port.out;

import dev.abgleich.application.port.in.StatementReport;
import java.util.Optional;
import java.util.UUID;

public interface StatementReportRepositoryPort {

    Optional<StatementReport> findReport(UUID importId);
}

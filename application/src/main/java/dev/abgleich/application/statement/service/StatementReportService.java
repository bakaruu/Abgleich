package dev.abgleich.application.statement.service;

import dev.abgleich.application.statement.StatementReport;
import dev.abgleich.application.statement.port.in.StatementReportQuery;
import dev.abgleich.application.statement.port.out.StatementReportRepositoryPort;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Keeps inbound adapters away from the persistence adapter: they only see this query (B40). */
public final class StatementReportService implements StatementReportQuery {

    private final StatementReportRepositoryPort reports;

    public StatementReportService(StatementReportRepositoryPort reports) {
        this.reports = Objects.requireNonNull(reports, "reports");
    }

    @Override
    public Optional<StatementReport> report(UUID importId) {
        return reports.findReport(Objects.requireNonNull(importId, "importId"));
    }
}

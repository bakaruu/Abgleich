package dev.abgleich.application.service;

import dev.abgleich.application.port.in.StatementReport;
import dev.abgleich.application.port.in.StatementReportQuery;
import dev.abgleich.application.port.out.StatementReportRepositoryPort;
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

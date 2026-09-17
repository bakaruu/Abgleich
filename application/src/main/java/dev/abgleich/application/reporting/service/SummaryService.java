package dev.abgleich.application.reporting.service;

import dev.abgleich.application.reporting.ReconciliationSummary;
import dev.abgleich.application.reporting.port.in.SummaryQuery;
import dev.abgleich.application.reporting.port.out.SummaryRepositoryPort;
import java.util.Objects;

/** Keeps inbound adapters and metrics away from the persistence adapter: they only see this query (B40). */
public final class SummaryService implements SummaryQuery {

    private final SummaryRepositoryPort summaries;

    public SummaryService(SummaryRepositoryPort summaries) {
        this.summaries = Objects.requireNonNull(summaries, "summaries");
    }

    @Override
    public ReconciliationSummary summary() {
        return summaries.summary();
    }
}

package dev.abgleich.application.service;

import dev.abgleich.application.port.in.ReconciliationSummary;
import dev.abgleich.application.port.in.SummaryQuery;
import dev.abgleich.application.port.out.SummaryRepositoryPort;
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

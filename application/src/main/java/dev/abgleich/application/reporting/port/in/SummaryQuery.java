package dev.abgleich.application.reporting.port.in;

import dev.abgleich.application.reporting.ReconciliationSummary;

/** The figures behind the Summary screen, the summary API and the metrics. */
public interface SummaryQuery {

    ReconciliationSummary summary();
}

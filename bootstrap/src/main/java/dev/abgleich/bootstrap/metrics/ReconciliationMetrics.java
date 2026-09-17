package dev.abgleich.bootstrap.metrics;

import dev.abgleich.application.StorageException;
import dev.abgleich.application.reporting.ReconciliationSummary;
import dev.abgleich.application.reporting.ReconciliationSummary.RuleOutcome;
import dev.abgleich.application.reporting.port.in.SummaryQuery;
import dev.abgleich.domain.matching.MatchRule;
import dev.abgleich.domain.money.Money;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Currency;
import java.util.List;
import java.util.Objects;
import java.util.function.ToDoubleFunction;

/**
 * Gauges read from the database, not counters kept in memory: every instance reports the same numbers and a
 * restart loses nothing. One summary query serves all gauges of a scrape and is reused for a few seconds.
 *
 * <p>Gauges are {@code double} because Prometheus only knows floating point. That is fine for a dashboard and
 * never flows back into the domain, where money stays {@code Money} (B01).
 */
public final class ReconciliationMetrics implements MeterBinder {

    static final List<Currency> CURRENCIES = List.of(Currency.getInstance("CHF"), Currency.getInstance("EUR"));
    private static final Duration REUSE_FOR = Duration.ofSeconds(5);

    private final SummaryQuery summaries;
    private final Clock clock;
    private ReconciliationSummary cached;
    private Instant cachedAt = Instant.MIN;

    public ReconciliationMetrics(SummaryQuery summaries, Clock clock) {
        this.summaries = Objects.requireNonNull(summaries, "summaries");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public void bindTo(MeterRegistry registry) {
        gauge(registry, "abgleich.auto.reconciliation.ratio", "Share of incoming payments settled without a person",
                summary -> summary.payments().credits() == 0 ? 0
                        : (double) summary.payments().autoConfirmed() / summary.payments().credits());
        gauge(registry, "abgleich.review.queue.size", "Payments waiting for a person",
                summary -> summary.payments().waitingForReview());
        gauge(registry, "abgleich.outbox.pending", "Events not published yet", ReconciliationSummary::outboxPending);
        for (Currency currency : CURRENCIES) {
            Gauge.builder("abgleich.unmatched.amount", this, metrics -> metrics.read(summary ->
                            amountIn(summary.unmatchedAmounts(), currency)))
                    .description("Money of incoming payments nobody could match yet")
                    .tag("currency", currency.getCurrencyCode())
                    .register(registry);
        }
        for (MatchRule rule : MatchRule.values()) {
            allocations(registry, rule, "auto_confirmed", RuleOutcome::autoConfirmed);
            allocations(registry, rule, "confirmed", RuleOutcome::confirmed);
            allocations(registry, rule, "rejected", RuleOutcome::rejected);
            allocations(registry, rule, "pending", RuleOutcome::pending);
        }
    }

    private void gauge(MeterRegistry registry, String name, String description,
            ToDoubleFunction<ReconciliationSummary> value) {
        Gauge.builder(name, this, metrics -> metrics.read(value)).description(description).register(registry);
    }

    private void allocations(MeterRegistry registry, MatchRule rule, String outcome,
            ToDoubleFunction<RuleOutcome> count) {
        Gauge.builder("abgleich.allocations", this, metrics -> metrics.read(summary ->
                        count.applyAsDouble(summary.rules().get(rule.ordinal()))))
                .description("Proposal groups per rule and outcome")
                .tag("rule", rule.name())
                .tag("outcome", outcome)
                .register(registry);
    }

    /** NaN when the database cannot be read: Prometheus shows a gap instead of a misleading zero. */
    private double read(ToDoubleFunction<ReconciliationSummary> value) {
        try {
            return value.applyAsDouble(summary());
        } catch (StorageException unavailable) {
            return Double.NaN;
        }
    }

    private synchronized ReconciliationSummary summary() {
        Instant now = clock.instant();
        if (cached == null || !now.isBefore(cachedAt.plus(REUSE_FOR))) {
            cached = summaries.summary();
            cachedAt = now;
        }
        return cached;
    }

    private static double amountIn(List<Money> amounts, Currency currency) {
        return amounts.stream()
                .filter(amount -> amount.currency().equals(currency))
                .mapToDouble(amount -> amount.amount().doubleValue())
                .sum();
    }
}

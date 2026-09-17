package dev.abgleich.bootstrap.config;

import dev.abgleich.application.reporting.port.in.SummaryQuery;
import dev.abgleich.bootstrap.metrics.ReconciliationMetrics;
import dev.abgleich.bootstrap.web.CorrelationIdFilter;
import java.time.Clock;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

/**
 * Business metrics for Prometheus at {@code /actuator/prometheus}. Import counts and durations come from the
 * decorator around statement processing; everything else is read from the database on each scrape.
 */
@Configuration(proxyBeanMethods = false)
class ObservabilityConfiguration {

    /** Runs before everything else, so even a refused request is logged with an id the visitor can quote. */
    @Bean
    FilterRegistrationBean<CorrelationIdFilter> correlationIdFilter() {
        FilterRegistrationBean<CorrelationIdFilter> registration =
                new FilterRegistrationBean<>(new CorrelationIdFilter());
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        registration.addUrlPatterns("/*");
        return registration;
    }

    @Bean
    ReconciliationMetrics reconciliationMetrics(SummaryQuery summaries, Clock clock) {
        return new ReconciliationMetrics(summaries, clock);
    }
}

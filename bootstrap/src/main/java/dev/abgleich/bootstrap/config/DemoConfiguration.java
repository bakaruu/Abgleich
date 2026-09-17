package dev.abgleich.bootstrap.config;

import dev.abgleich.adapter.in.scheduler.DemoResetJob;
import dev.abgleich.adapter.out.postgres.JdbcDemoDataRepository;
import dev.abgleich.application.example.port.in.ExampleDataUseCase;
import dev.abgleich.application.example.port.in.ResetDemoUseCase;
import dev.abgleich.application.example.port.out.DemoDataPort;
import dev.abgleich.application.example.service.DemoResetService;
import dev.abgleich.bootstrap.web.RateLimitFilter;
import dev.abgleich.bootstrap.web.RequestRateLimiter;
import java.time.Clock;
import java.time.Duration;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.util.unit.DataSize;

/**
 * The public demo ({@code demo} profile): nightly reset and disk quota (B43, B44). The request rate limit has
 * its own switch so it can be used without demo mode.
 */
class DemoConfiguration {

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(name = "abgleich.demo.enabled", havingValue = "true")
    static class Reset {

        @Bean
        JdbcDemoDataRepository demoDataRepository(DataSource dataSource) {
            return new JdbcDemoDataRepository(dataSource);
        }

        @Bean
        DemoResetService demoResetService(DemoDataPort data, ExampleDataUseCase examples) {
            return new DemoResetService(data, examples);
        }

        @Bean
        DemoResetJob demoResetJob(ResetDemoUseCase resetDemo,
                @Value("${abgleich.demo.max-stored-data}") DataSize maxStoredData) {
            return new DemoResetJob(resetDemo, maxStoredData.toBytes());
        }
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(name = "abgleich.rate-limit.enabled", havingValue = "true")
    static class RateLimit {

        /** Runs before Spring Security, so refused requests cost as little as possible. */
        @Bean
        FilterRegistrationBean<RateLimitFilter> rateLimitFilter(
                @Value("${abgleich.rate-limit.changes-per-window}") int changesPerWindow,
                @Value("${abgleich.rate-limit.window}") Duration window,
                @Value("${abgleich.rate-limit.max-tracked-clients:10000}") int maxTrackedClients, Clock clock) {
            FilterRegistrationBean<RateLimitFilter> registration = new FilterRegistrationBean<>(
                    new RateLimitFilter(new RequestRateLimiter(changesPerWindow, window, clock, maxTrackedClients)));
            registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
            registration.addUrlPatterns("/*");
            return registration;
        }
    }
}

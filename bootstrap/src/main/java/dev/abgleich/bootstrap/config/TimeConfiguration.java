package dev.abgleich.bootstrap.config;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The single place where the system clock is created. Domain and use cases receive this
 * {@link Clock} instead of calling {@code now()} themselves, so tests control time (B38).
 */
@Configuration(proxyBeanMethods = false)
class TimeConfiguration {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}

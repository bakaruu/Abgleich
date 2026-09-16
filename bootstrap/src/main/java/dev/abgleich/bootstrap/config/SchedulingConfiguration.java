package dev.abgleich.bootstrap.config;

import javax.sql.DataSource;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.provider.jdbctemplate.JdbcTemplateLockProvider;
import net.javacrumbs.shedlock.spring.annotation.EnableSchedulerLock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * B26: every scheduled job holds a lock in the {@code shedlock} table while it runs, so with several instances
 * only one of them downloads, polls or relays at a time. Lock times come from the database clock, not from the
 * clocks of the instances.
 *
 * <p>Locking is always on; the schedule itself can be switched off ({@code abgleich.scheduling.enabled=false}),
 * which tests do to trigger jobs themselves.
 */
@Configuration(proxyBeanMethods = false)
@EnableSchedulerLock(defaultLockAtMostFor = "PT10M")
class SchedulingConfiguration {

    @Bean
    LockProvider lockProvider(DataSource dataSource) {
        return new JdbcTemplateLockProvider(JdbcTemplateLockProvider.Configuration.builder()
                .withJdbcTemplate(new JdbcTemplate(dataSource))
                .usingDbTime()
                .build());
    }

    @Configuration(proxyBeanMethods = false)
    @EnableScheduling
    @ConditionalOnProperty(name = "abgleich.scheduling.enabled", havingValue = "true", matchIfMissing = true)
    static class Schedule {
    }
}

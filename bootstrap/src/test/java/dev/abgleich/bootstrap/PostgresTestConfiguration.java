package dev.abgleich.bootstrap;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * One PostgreSQL container for every Spring Boot test. Test classes use the same annotations, so
 * Spring reuses a single application context and the named container is created only once.
 */
@TestConfiguration(proxyBeanMethods = false)
class PostgresTestConfiguration {

    @Bean
    @ServiceConnection
    PostgreSQLContainer postgres() {
        return new PostgreSQLContainer("postgres:17-alpine")
                .withCreateContainerCmdModifier(cmd -> cmd.withName("abgleich-test-postgres-application"));
    }
}

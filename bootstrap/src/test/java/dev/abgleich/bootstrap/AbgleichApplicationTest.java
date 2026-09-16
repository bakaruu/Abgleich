package dev.abgleich.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.postgresql.PostgreSQLContainer;

@SpringBootTest
@Import(AbgleichApplicationTest.PostgresTestConfiguration.class)
class AbgleichApplicationTest {

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void starts_and_applies_database_migrations() {
        Integer tables = jdbc.queryForObject("""
                select count(*) from information_schema.tables
                where table_schema = 'public'
                  and table_name in ('statement_import', 'bank_transaction', 'invoice', 'allocation')
                """, Integer.class);

        assertThat(tables).isEqualTo(4);
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class PostgresTestConfiguration {

        @Bean
        @ServiceConnection
        PostgreSQLContainer postgres() {
            return new PostgreSQLContainer("postgres:17-alpine")
                    .withCreateContainerCmdModifier(cmd -> cmd.withName("abgleich-test-postgres-application"));
        }
    }
}

package dev.abgleich.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

@AbgleichIntegrationTest
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
}

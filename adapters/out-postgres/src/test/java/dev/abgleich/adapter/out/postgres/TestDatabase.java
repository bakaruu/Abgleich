package dev.abgleich.adapter.out.postgres;

import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * One migrated PostgreSQL shared by the repository tests of this module, started on first use and
 * removed when the test JVM exits.
 */
final class TestDatabase {

    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine")
            .withCreateContainerCmdModifier(cmd -> cmd.withName("abgleich-test-postgres-repositories"));

    private static DataSource dataSource;

    private TestDatabase() {
    }

    static synchronized DataSource dataSource() {
        if (dataSource == null) {
            POSTGRES.start();
            // Ryuk removes containers only some seconds after the JVM exits; a test run started right
            // after would find the fixed container name still taken. Stopping here removes it at once.
            Runtime.getRuntime().addShutdownHook(new Thread(POSTGRES::stop, "stop-abgleich-test-postgres"));
            PGSimpleDataSource pg = new PGSimpleDataSource();
            pg.setUrl(POSTGRES.getJdbcUrl());
            pg.setUser(POSTGRES.getUsername());
            pg.setPassword(POSTGRES.getPassword());
            Flyway.configure().dataSource(pg).load().migrate();
            dataSource = pg;
        }
        return dataSource;
    }

    static TransactionTemplate transactions() {
        return new TransactionTemplate(new DataSourceTransactionManager(dataSource()));
    }

    static JdbcTemplate emptied() {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource());
        jdbc.execute("truncate allocation, bank_transaction, invoice, statement_import");
        return jdbc;
    }
}

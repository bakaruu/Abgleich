package dev.abgleich.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;

import dev.abgleich.adapter.in.scheduler.OutboxRelayJob;
import dev.abgleich.adapter.in.scheduler.StatementFetchJob;
import dev.abgleich.adapter.in.sftp.EmbeddedSftpServer;
import dev.abgleich.adapter.in.sftp.SftpStatementWatcher;
import dev.abgleich.application.port.in.ExampleDataUseCase;
import dev.abgleich.mockbank.MockBank;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Duration;
import java.time.Instant;
import javax.sql.DataSource;
import net.javacrumbs.shedlock.core.LockConfiguration;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.core.SimpleLock;
import net.javacrumbs.shedlock.provider.jdbctemplate.JdbcTemplateLockProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * B26: two instances of the application share the database. While one of them runs a scheduled job, the other
 * one skips it. The other instance is simulated by taking the lock with a lock provider of its own; the jobs are
 * the beans of this application, called the way the scheduler calls them.
 */
@AbgleichIntegrationTest
class ScheduledJobsLockTest {

    @Autowired
    StatementFetchJob fetchJob;

    @Autowired
    OutboxRelayJob relayJob;

    @Autowired
    SftpStatementWatcher sftpWatcher;

    @Autowired
    MockBank bank;

    @Autowired
    EmbeddedSftpServer sftp;

    @Autowired
    ExampleDataUseCase examples;

    @Autowired
    DataSource dataSource;

    @Autowired
    JdbcTemplate jdbc;

    private LockProvider otherInstance;

    @BeforeEach
    void setUp() {
        jdbc.execute("truncate outbox_event, processed_message, allocation, bank_transaction, invoice, statement_import, shedlock");
        bank.clear();
        sftp.clear();
        otherInstance = new JdbcTemplateLockProvider(JdbcTemplateLockProvider.Configuration.builder()
                .withJdbcTemplate(new JdbcTemplate(dataSource)).usingDbTime().build());
    }

    @Test
    void B26_fetch_job_is_skipped_while_another_instance_runs_it() {
        SimpleLock held = lock("fetch-bank-statements");

        fetchJob.run();
        assertThat(bank.listRequests()).as("the bank is not asked twice").isZero();

        held.unlock();
        fetchJob.run();
        assertThat(bank.listRequests()).as("one listing per configured account").isEqualTo(3);
    }

    @Test
    void B26_outbox_relay_is_skipped_while_another_instance_runs_it() {
        examples.load();
        SimpleLock held = lock("outbox-relay");

        relayJob.run();
        assertThat(unpublished()).isPositive();

        held.unlock();
        relayJob.run();
        assertThat(unpublished()).isZero();
    }

    @Test
    void B26_sftp_poll_is_skipped_while_another_instance_runs_it() throws Exception {
        Files.writeString(sftp.inbox().resolve("unknown.txt"), "not a statement", StandardCharsets.UTF_8);
        Files.writeString(sftp.inbox().resolve("unknown.txt.done"), "", StandardCharsets.UTF_8);
        SimpleLock held = lock("sftp-statement-inbox");

        sftpWatcher.scheduledPoll();
        assertThat(sftp.inbox().resolve("unknown.txt")).exists();

        held.unlock();
        sftpWatcher.scheduledPoll();
        assertThat(sftp.inbox().resolve("unknown.txt")).as("moved to error/").doesNotExist();
    }

    private SimpleLock lock(String name) {
        return otherInstance.lock(new LockConfiguration(Instant.now(), name, Duration.ofMinutes(5), Duration.ZERO))
                .orElseThrow();
    }

    private long unpublished() {
        return jdbc.queryForObject("select count(*) from outbox_event where published_at is null", Long.class);
    }
}

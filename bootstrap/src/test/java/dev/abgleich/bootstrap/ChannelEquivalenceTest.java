package dev.abgleich.bootstrap;

import static dev.abgleich.bootstrap.Browser.map;
import static org.assertj.core.api.Assertions.assertThat;

import dev.abgleich.adapter.in.sftp.EmbeddedSftpServer;
import dev.abgleich.adapter.in.sftp.SftpStatementWatcher;
import dev.abgleich.application.port.in.FetchStatementsUseCase;
import dev.abgleich.application.port.in.ImportSource;
import dev.abgleich.application.port.in.RegisterInvoiceUseCase;
import dev.abgleich.application.port.out.ExampleDataPort;
import dev.abgleich.application.port.out.ExampleFile;
import dev.abgleich.mockbank.MockBank;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The same statement files reach the same reconciliation, whichever way they arrive: uploaded in the browser,
 * posted to the API, dropped on the SFTP server or downloaded from the bank. Every channel is only an adapter
 * in front of the same use case; this test fails if one of them starts to behave differently.
 */
@AbgleichIntegrationTest
class ChannelEquivalenceTest {

    @Value("${local.server.port}")
    int port;

    @Autowired
    ExampleDataPort examples;

    @Autowired
    RegisterInvoiceUseCase registerInvoice;

    @Autowired
    SftpStatementWatcher sftpWatcher;

    @Autowired
    EmbeddedSftpServer sftp;

    @Autowired
    MockBank bank;

    @Autowired
    FetchStatementsUseCase fetchStatements;

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void same_files_give_the_same_reconciliation_through_web_rest_sftp_and_bank_api() {
        List<ExampleFile> files = examples.exampleData().files();
        Map<ImportSource, String> reports = new EnumMap<>(ImportSource.class);

        reports.put(ImportSource.WEB, reconcileVia(ImportSource.WEB, () -> {
            Browser browser = new Browser(port);
            String csrf = browser.csrfToken();
            files.forEach(file -> assertThat(browser.upload("/statements", file.content(), map("_csrf", csrf), Map.of())
                    .statusCode()).as(file.name()).isEqualTo(200));
        }));
        reports.put(ImportSource.REST, reconcileVia(ImportSource.REST, () -> {
            Browser api = new Browser(port);
            files.forEach(file -> assertThat(api.upload("/api/v1/statements", file.content(), Map.of(), Map.of())
                    .statusCode()).as(file.name()).isEqualTo(201));
        }));
        reports.put(ImportSource.SFTP, reconcileVia(ImportSource.SFTP, () -> {
            files.forEach(file -> drop(file));
            assertThat(sftpWatcher.pollInbox().processed()).isEqualTo(files.size());
            assertThat(sftp.offeredPublicKeys()).as("logs in with its configured password, never with keys from ~/.ssh")
                    .isEmpty();
        }));
        reports.put(ImportSource.BANK_API, reconcileVia(ImportSource.BANK_API, () -> {
            LocalDate today = LocalDate.now(ZoneOffset.UTC);
            for (ExampleFile file : files) {
                accountsOf(file).forEach(account -> bank.publish(account, today, file.content()));
            }
            assertThat(fetchStatements.fetchLatest().accounts())
                    .allSatisfy(account -> assertThat(account.bankUnavailable()).isFalse());
        }));

        String web = reports.get(ImportSource.WEB);
        assertThat(web).as("a meaningful reconciliation").contains("MATCHED -> ").contains("PROPOSED -> ");
        assertThat(reports).allSatisfy((source, report) -> assertThat(report).as(source.name()).isEqualTo(web));
    }

    private String reconcileVia(ImportSource source, Runnable deliverFiles) {
        jdbc.execute("truncate outbox_event, processed_message, allocation, bank_transaction, invoice, statement_import");
        sftp.clear();
        bank.clear();
        examples.exampleData().invoices().forEach(registerInvoice::register);

        deliverFiles.run();

        assertThat(jdbc.queryForList("select distinct source from statement_import", String.class))
                .as("every import is recorded with its channel").containsExactly(source.name());
        return ReconciliationReport.render(jdbc);
    }

    private void drop(ExampleFile file) {
        try {
            Files.write(sftp.inbox().resolve(file.name()), file.content());
            Files.write(sftp.inbox().resolve(file.name() + ".done"), new byte[0]);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** The example camt.053 holds both Swiss accounts; the Norma 43 file the Spanish one. */
    private static List<String> accountsOf(ExampleFile file) {
        return file.name().contains("norma43")
                ? List.of("ES9121000418450200051332")
                : List.of("CH4431999123000889012", "CH9300762011623852957");
    }
}

package dev.abgleich.bootstrap.metrics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import dev.abgleich.application.reconciliation.port.in.ReconciliationRun;
import dev.abgleich.application.statement.ImportSource;
import dev.abgleich.application.statement.ImportedStatement;
import dev.abgleich.application.statement.StatementFormat;
import dev.abgleich.application.statement.port.in.ImportResult;
import dev.abgleich.application.statement.port.in.ImportStatementCommand;
import dev.abgleich.application.statement.port.in.ProcessStatementUseCase;
import dev.abgleich.application.statement.port.in.StatementProcessed;
import dev.abgleich.domain.account.Iban;
import dev.abgleich.domain.money.Money;
import dev.abgleich.domain.statement.Balance;
import dev.abgleich.domain.statement.InvalidStatementException;
import java.io.ByteArrayInputStream;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

class LoggedProcessStatementTest {

    private static final UUID IMPORT_ID = UUID.fromString("0f1e2d3c-4b5a-4c7d-8e9f-a0b1c2d3e4f5");
    private static final Iban ACCOUNT = Iban.of("CH9300762011623852957");
    private static final String DEBTOR = "Keller GmbH";

    private final Logger logger = (Logger) LoggerFactory.getLogger(LoggedProcessStatement.class);
    private final ListAppender<ILoggingEvent> lines = new ListAppender<>();

    @BeforeEach
    void captureLogs() {
        lines.start();
        logger.addAppender(lines);
    }

    @AfterEach
    void stopCapturing() {
        logger.detachAppender(lines);
    }

    @Test
    void an_imported_file_is_logged_once_with_the_ids_needed_to_follow_it() {
        ProcessStatementUseCase logged = new LoggedProcessStatement(command -> processed());

        logged.process(command());

        ILoggingEvent line = lines.list.getFirst();
        assertThat(lines.list).hasSize(1);
        assertThat(line.getLevel()).isEqualTo(Level.INFO);
        assertThat(line.getFormattedMessage())
                .contains("source=WEB", "format=CAMT053_V04", "outcome=IMPORTED", "newTransactions=12",
                        "autoConfirmed=7", "sentToReview=3", "unmatched=2");
        assertThat(line.getMDCPropertyMap()).containsEntry("import.id", IMPORT_ID.toString());
    }

    /** B41: counts and ids are safe to log; what the bank file says about people is not. */
    @Test
    void nothing_from_the_file_reaches_the_log() {
        ProcessStatementUseCase logged = new LoggedProcessStatement(command -> processed());

        logged.process(command());

        assertThat(lines.list.getFirst().getFormattedMessage())
                .doesNotContain(DEBTOR)
                .doesNotContain("CH9300762011623852957");
    }

    @Test
    void a_rejected_file_is_logged_as_a_warning_and_still_rejected() {
        InvalidStatementException rejected = new InvalidStatementException(
                InvalidStatementException.Reason.UNBALANCED, "Balances do not add up");
        ProcessStatementUseCase logged = new LoggedProcessStatement(command -> {
            throw rejected;
        });

        assertThatThrownBy(() -> logged.process(command())).isSameAs(rejected);

        assertThat(lines.list).singleElement().satisfies(line -> {
            assertThat(line.getLevel()).isEqualTo(Level.WARN);
            assertThat(line.getFormattedMessage()).contains("source=WEB", "Balances do not add up");
        });
    }

    private static ImportStatementCommand command() {
        return new ImportStatementCommand(ImportSource.WEB, () -> new ByteArrayInputStream(new byte[] {1, 2, 3}));
    }

    private static StatementProcessed processed() {
        Balance opening = new Balance(Money.chf("1000.00"), LocalDate.of(2026, 11, 20));
        Balance closing = new Balance(Money.chf("2500.00"), LocalDate.of(2026, 11, 21));
        ImportedStatement statement = new ImportedStatement(IMPORT_ID, ACCOUNT, opening, closing, 12, 4);
        return new StatementProcessed(
                new ImportResult(ImportResult.Outcome.IMPORTED, StatementFormat.CAMT053_V04, List.of(statement)),
                List.of(new ReconciliationRun(12, 7, 3, 2, 0, 0, 0)));
    }
}

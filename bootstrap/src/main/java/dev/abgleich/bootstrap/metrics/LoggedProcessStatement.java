package dev.abgleich.bootstrap.metrics;

import dev.abgleich.application.statement.ImportedStatement;
import dev.abgleich.application.statement.port.in.ImportStatementCommand;
import dev.abgleich.application.statement.port.in.ProcessStatementUseCase;
import dev.abgleich.application.statement.port.in.StatementProcessed;
import dev.abgleich.application.reconciliation.port.in.ReconciliationRun;
import dev.abgleich.domain.statement.InvalidStatementException;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

/**
 * One log line per statement file, carrying the ids needed to follow it afterwards: the correlation id of the
 * request that brought it, and the import id stored in the database.
 *
 * <p>A decorator in the wiring module, like {@link MeteredProcessStatement}: the use case knows nothing about
 * logging. Only ids and counts are logged, never account numbers, names or anything read from the file (B41).
 */
public final class LoggedProcessStatement implements ProcessStatementUseCase {

    /** ECS-style field, so a file can be followed across log lines as {@code import.id}. */
    static final String MDC_KEY = "import.id";

    private static final Logger log = LoggerFactory.getLogger(LoggedProcessStatement.class);

    private final ProcessStatementUseCase delegate;

    public LoggedProcessStatement(ProcessStatementUseCase delegate) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
    }

    @Override
    public StatementProcessed process(ImportStatementCommand command) {
        try {
            StatementProcessed processed = delegate.process(command);
            MDC.put(MDC_KEY, importIds(processed));
            try {
                log.info("Statement processed: source={} format={} outcome={} accounts={} newTransactions={} {}",
                        command.source(), processed.imported().format(), processed.imported().outcome(),
                        processed.imported().statements().size(), newTransactions(processed),
                        summary(processed.reconciliations()));
            } finally {
                MDC.remove(MDC_KEY);
            }
            return processed;
        } catch (InvalidStatementException rejected) {
            log.warn("Statement rejected: source={} reason={}", command.source(), rejected.getMessage());
            throw rejected;
        }
    }

    /** One import per account of the file, so a file with two accounts carries both ids. */
    private static String importIds(StatementProcessed processed) {
        return processed.imported().statements().stream()
                .map(ImportedStatement::importId)
                .map(Objects::toString)
                .collect(Collectors.joining(","));
    }

    private static int newTransactions(StatementProcessed processed) {
        return processed.imported().statements().stream().mapToInt(ImportedStatement::newTransactions).sum();
    }

    private static String summary(List<ReconciliationRun> runs) {
        return "autoConfirmed=" + runs.stream().mapToInt(ReconciliationRun::autoConfirmed).sum()
                + " sentToReview=" + runs.stream().mapToInt(ReconciliationRun::sentToReview).sum()
                + " unmatched=" + runs.stream().mapToInt(ReconciliationRun::unmatched).sum();
    }
}

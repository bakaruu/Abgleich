package dev.abgleich.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;

import dev.abgleich.adapter.out.synthetic.LoadDataset;
import dev.abgleich.application.invoice.port.in.RegisterInvoiceUseCase;
import dev.abgleich.application.statement.ImportSource;
import dev.abgleich.application.statement.port.in.ImportStatementCommand;
import dev.abgleich.application.statement.port.in.ProcessStatementUseCase;
import dev.abgleich.application.statement.port.in.StatementProcessed;
import dev.abgleich.application.statement.port.out.ParsedStatementFile;
import dev.abgleich.application.statement.port.out.StatementParserPort;
import dev.abgleich.application.statement.port.out.StatementSniff;
import dev.abgleich.domain.matching.Matcher;
import dev.abgleich.domain.matching.PaymentToMatch;
import java.io.ByteArrayInputStream;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * How fast the system is with a statement far larger than a real day's file, measured instead of guessed.
 *
 * <p>Not part of {@code ./gradlew build}: it takes minutes and its numbers depend on the machine. Run it with
 * {@code ./gradlew benchmark} (optionally {@code -Pbenchmark.transactions=20000}) and read the table it prints.
 * The dataset is synthetic (B41) and deterministic, so two runs on the same machine compare fairly.
 */
@AbgleichIntegrationTest
class PerformanceBenchmark {

    private static final int TRANSACTIONS =
            Integer.getInteger("benchmark.transactions", 5_000);
    private static final long SEED = 20261118L;
    private static final java.time.Instant NOW = java.time.Instant.parse("2026-10-15T20:00:00Z");

    @Autowired
    List<StatementParserPort> parsers;

    @Autowired
    RegisterInvoiceUseCase registerInvoice;

    @Autowired
    ProcessStatementUseCase processStatement;

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void measure() {
        LoadDataset dataset = LoadDataset.of(TRANSACTIONS, SEED);
        System.out.println("\nAbgleich performance, " + dataset.describe());
        System.out.println("=".repeat(72));

        Duration parsing = measureParsing(dataset);
        report("Parse camt.053 only (StAX, no database)", parsing, dataset.transactions());
        System.out.printf(Locale.ROOT, "  %-52s %,10.1f MB/s%n", "throughput",
                dataset.sizeInBytes() / (1024.0 * 1024.0) / seconds(parsing));

        jdbc.execute("truncate outbox_event, processed_message, allocation, bank_transaction, invoice, statement_import");
        Duration registering = measureInvoiceRegistration(dataset);
        report("Register the invoices the payments will settle", registering, dataset.invoices().size());

        Duration matching = measureMatching(dataset);
        report("Match every payment in memory (no database)", matching, dataset.transactions());

        Duration processing = measureProcessing(dataset);
        report("Import and reconcile the whole file", processing, dataset.transactions());

        System.out.println("=".repeat(72));
        System.out.printf(Locale.ROOT, "  %-52s %,10d%n", "transactions stored", count("bank_transaction"));
        System.out.printf(Locale.ROOT, "  %-52s %,10d%n", "allocations decided", count("allocation"));
        System.out.println();
    }

    private Duration measureParsing(LoadDataset dataset) {
        StatementParserPort parser = parserFor(dataset);
        parse(parser, dataset); // warm up the JIT and the XML factories
        long start = System.nanoTime();
        ParsedStatementFile parsed = parse(parser, dataset);
        Duration elapsed = Duration.ofNanos(System.nanoTime() - start);
        assertThat(parsed.statements()).singleElement()
                .satisfies(statement -> assertThat(statement.entries()).hasSize(dataset.transactions()));
        return elapsed;
    }

    private Duration measureInvoiceRegistration(LoadDataset dataset) {
        long start = System.nanoTime();
        dataset.invoices().forEach(registerInvoice::register);
        return Duration.ofNanos(System.nanoTime() - start);
    }

    /** The matcher alone, over the same payments and the same open invoices: the cost that is not the database. */
    private Duration measureMatching(LoadDataset dataset) {
        Matcher matcher = new Matcher();
        matcher.decide(dataset.payments().getFirst(), dataset.openInvoices(), NOW); // warm up
        long start = System.nanoTime();
        for (PaymentToMatch payment : dataset.payments()) {
            matcher.decide(payment, dataset.openInvoices(), NOW);
        }
        return Duration.ofNanos(System.nanoTime() - start);
    }

    private Duration measureProcessing(LoadDataset dataset) {
        long start = System.nanoTime();
        StatementProcessed processed = processStatement.process(
                new ImportStatementCommand(ImportSource.REST, () -> new ByteArrayInputStream(dataset.camtFile())));
        Duration elapsed = Duration.ofNanos(System.nanoTime() - start);
        assertThat(processed.imported().statements()).singleElement()
                .satisfies(statement -> assertThat(statement.newTransactions()).isEqualTo(dataset.transactions()));
        return elapsed;
    }

    private StatementParserPort parserFor(LoadDataset dataset) {
        StatementSniff sniff = StatementSniff.of(dataset.camtFile());
        return parsers.stream()
                .filter(parser -> parser.canParse(sniff))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("No parser recognises the generated file"));
    }

    private static ParsedStatementFile parse(StatementParserPort parser, LoadDataset dataset) {
        return parser.parse(new ByteArrayInputStream(dataset.camtFile()));
    }

    private static void report(String what, Duration elapsed, int items) {
        System.out.printf(Locale.ROOT, "%n%s%n  %-52s %,10d ms%n  %-52s %,10.0f /s%n", what,
                "time", elapsed.toMillis(), "throughput", items / seconds(elapsed));
    }

    private static double seconds(Duration elapsed) {
        return elapsed.toNanos() / 1_000_000_000.0;
    }

    private int count(String table) {
        return jdbc.queryForObject("select count(*) from " + table, Integer.class);
    }
}

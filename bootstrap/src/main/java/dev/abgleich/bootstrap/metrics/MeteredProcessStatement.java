package dev.abgleich.bootstrap.metrics;

import dev.abgleich.application.statement.port.in.ImportStatementCommand;
import dev.abgleich.application.statement.port.in.ProcessStatementUseCase;
import dev.abgleich.application.statement.port.in.StatementProcessed;
import dev.abgleich.domain.statement.InvalidStatementException;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.Locale;
import java.util.Objects;

/**
 * Counts statement files per channel, format and result, and times their processing. A decorator in the wiring
 * module: the use case itself knows nothing about metrics.
 *
 * <p>{@code abgleich_imports_total{source,format,result}} and {@code abgleich_import_duration_seconds{format}}.
 */
public final class MeteredProcessStatement implements ProcessStatementUseCase {

    static final String IMPORTS = "abgleich.imports";
    static final String DURATION = "abgleich.import.duration";

    private final ProcessStatementUseCase delegate;
    private final MeterRegistry registry;

    public MeteredProcessStatement(ProcessStatementUseCase delegate, MeterRegistry registry) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.registry = Objects.requireNonNull(registry, "registry");
    }

    @Override
    public StatementProcessed process(ImportStatementCommand command) {
        Timer.Sample sample = Timer.start(registry);
        String source = tag(command.source().name());
        try {
            StatementProcessed processed = delegate.process(command);
            String format = tag(processed.imported().format().name());
            record(sample, source, format, tag(processed.imported().outcome().name()));
            return processed;
        } catch (InvalidStatementException rejected) {
            record(sample, source, "unknown", "rejected");
            throw rejected;
        } catch (RuntimeException failed) {
            record(sample, source, "unknown", "failed");
            throw failed;
        }
    }

    private void record(Timer.Sample sample, String source, String format, String result) {
        registry.counter(IMPORTS, "source", source, "format", format, "result", result).increment();
        sample.stop(Timer.builder(DURATION)
                .description("Time to import and reconcile one statement file")
                .tag("format", format)
                .register(registry));
    }

    private static String tag(String value) {
        return value.toLowerCase(Locale.ROOT);
    }
}

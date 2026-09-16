package dev.abgleich.application.service;

import dev.abgleich.application.port.in.ExampleDataUseCase;
import dev.abgleich.application.port.in.ImportSource;
import dev.abgleich.application.port.in.ImportStatementCommand;
import dev.abgleich.application.port.in.ProcessStatementUseCase;
import dev.abgleich.application.port.in.RegisterInvoiceCommand;
import dev.abgleich.application.port.in.RegisterInvoiceUseCase;
import dev.abgleich.application.port.out.DuplicateInvoiceException;
import dev.abgleich.application.port.out.ExampleDataPort;
import dev.abgleich.application.port.out.ExampleDataPort.ExampleData;
import dev.abgleich.application.port.out.ExampleFile;
import java.io.ByteArrayInputStream;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Registers the example invoices first, then processes the files like any upload, so the example
 * goes through the same parsers, constraints and rules as real data.
 */
public final class ExampleDataService implements ExampleDataUseCase {

    private final ExampleDataPort examples;
    private final RegisterInvoiceUseCase registerInvoice;
    private final ProcessStatementUseCase processStatement;

    public ExampleDataService(ExampleDataPort examples, RegisterInvoiceUseCase registerInvoice,
            ProcessStatementUseCase processStatement) {
        this.examples = Objects.requireNonNull(examples, "examples");
        this.registerInvoice = Objects.requireNonNull(registerInvoice, "registerInvoice");
        this.processStatement = Objects.requireNonNull(processStatement, "processStatement");
    }

    @Override
    public ExampleLoaded load() {
        ExampleData data = examples.exampleData();
        int registered = 0;
        for (RegisterInvoiceCommand invoice : data.invoices()) {
            try {
                registerInvoice.register(invoice);
                registered++;
            } catch (DuplicateInvoiceException alreadyThere) {
                // Loaded before: the database refused the number (B24), which is exactly what we want.
            }
        }
        List<FileProcessed> processed = data.files().stream()
                .map(file -> new FileProcessed(file, processStatement.process(new ImportStatementCommand(
                        ImportSource.EXAMPLE, () -> new ByteArrayInputStream(file.content())))))
                .toList();
        return new ExampleLoaded(registered, data.invoices().size() - registered, processed);
    }

    @Override
    public List<ExampleFile> files() {
        return examples.exampleData().files();
    }

    @Override
    public Optional<ExampleFile> file(String name) {
        return files().stream().filter(file -> file.name().equals(name)).findFirst();
    }
}

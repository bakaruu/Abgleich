package dev.abgleich.application.example.port.out;

import dev.abgleich.application.example.ExampleFile;
import dev.abgleich.application.invoice.RegisterInvoiceCommand;
import java.util.List;
import java.util.Objects;

/** Synthetic invoices and the statement files that pay them. Only invented data, never real (B41). */
public interface ExampleDataPort {

    ExampleData exampleData();

    record ExampleData(List<RegisterInvoiceCommand> invoices, List<ExampleFile> files) {
        public ExampleData {
            Objects.requireNonNull(invoices, "invoices");
            Objects.requireNonNull(files, "files");
            invoices = List.copyOf(invoices);
            files = List.copyOf(files);
        }
    }
}

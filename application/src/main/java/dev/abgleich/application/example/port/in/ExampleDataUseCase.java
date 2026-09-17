package dev.abgleich.application.example.port.in;

import dev.abgleich.application.example.ExampleFile;
import dev.abgleich.application.statement.port.in.StatementProcessed;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Lets anyone try Abgleich without their own files: load synthetic invoices and statements in one
 * step, or download the files to upload them by hand. Loading twice changes nothing.
 */
public interface ExampleDataUseCase {

    /** Loads the Swiss and the Spanish example. */
    ExampleLoaded load();

    /**
     * Loads only the example of one country: its invoices and its statement file.
     *
     * @param country ISO 3166 code, such as CH or ES
     * @throws IllegalArgumentException if there is no example for the country
     */
    ExampleLoaded load(String country);

    List<ExampleFile> files();

    Optional<ExampleFile> file(String name);

    /** @param invoicesAlreadyPresent invoices skipped because their number was registered before */
    record ExampleLoaded(int invoicesRegistered, int invoicesAlreadyPresent, List<FileProcessed> files) {
        public ExampleLoaded {
            files = List.copyOf(files);
        }
    }

    record FileProcessed(ExampleFile file, StatementProcessed processed) {
        public FileProcessed {
            Objects.requireNonNull(file, "file");
            Objects.requireNonNull(processed, "processed");
        }
    }
}

package dev.abgleich.application.port.in;

import dev.abgleich.application.port.out.ExampleFile;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Lets anyone try Abgleich without their own files: load synthetic invoices and statements in one
 * step, or download the files to upload them by hand. Loading twice changes nothing.
 */
public interface ExampleDataUseCase {

    ExampleLoaded load();

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

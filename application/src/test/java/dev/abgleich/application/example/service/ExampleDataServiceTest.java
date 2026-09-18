package dev.abgleich.application.example.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.abgleich.application.example.ExampleFile;
import dev.abgleich.application.example.port.in.ExampleDataUseCase.ExampleLoaded;
import dev.abgleich.application.example.port.in.ExampleDataUseCase.FileProcessed;
import dev.abgleich.application.example.port.out.ExampleDataPort;
import dev.abgleich.application.invoice.DuplicateInvoiceException;
import dev.abgleich.application.invoice.RegisterInvoiceCommand;
import dev.abgleich.application.invoice.port.in.RegisterInvoiceUseCase;
import dev.abgleich.application.reconciliation.port.in.ReconciliationRun;
import dev.abgleich.application.statement.ImportSource;
import dev.abgleich.application.statement.ImportedStatement;
import dev.abgleich.application.statement.StatementFormat;
import dev.abgleich.application.statement.port.in.ImportResult;
import dev.abgleich.application.statement.port.in.ImportStatementCommand;
import dev.abgleich.application.statement.port.in.ProcessStatementUseCase;
import dev.abgleich.application.statement.port.in.StatementProcessed;
import dev.abgleich.domain.account.Iban;
import dev.abgleich.domain.invoice.InvoiceNumber;
import dev.abgleich.domain.money.Money;
import dev.abgleich.domain.reference.PaymentReference;
import dev.abgleich.domain.statement.Balance;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * "Load example" must behave exactly like a real upload, and pressing it twice must change nothing: the second
 * time the database refuses the invoice numbers (B24) and the files are recognised as already imported (B21).
 */
class ExampleDataServiceTest {

    private static final Iban SWISS = Iban.of("CH9300762011623852957");
    private static final Iban SPANISH = Iban.of("ES9121000418450200051332");
    private static final LocalDate DUE = LocalDate.of(2026, 9, 30);

    private final FakeInvoices invoices = new FakeInvoices();
    private final FakeProcessing processing = new FakeProcessing();
    private final ExampleDataService service =
            new ExampleDataService(new FakeExamples(), invoices, processing);

    @Test
    void loading_the_example_registers_the_invoices_and_processes_every_file() {
        ExampleLoaded loaded = service.load();

        assertThat(loaded.invoicesRegistered()).isEqualTo(3);
        assertThat(loaded.invoicesAlreadyPresent()).isZero();
        assertThat(loaded.files()).extracting(FileProcessed::file).extracting(ExampleFile::name)
                .containsExactly("swiss.xml", "spanish.n43");
        assertThat(processing.sources).containsOnly(ImportSource.EXAMPLE);
        assertThat(processing.bytes).containsExactly("swiss", "spanish");
    }

    @Test
    void B24_loading_it_twice_registers_nothing_new_and_says_so() {
        service.load();

        ExampleLoaded again = service.load();

        assertThat(again.invoicesRegistered()).isZero();
        assertThat(again.invoicesAlreadyPresent()).isEqualTo(3);
        assertThat(again.files()).hasSize(2);
        assertThat(invoices.registered).as("still only the three original invoices").hasSize(3);
    }

    @Test
    void one_country_loads_only_its_own_invoices_and_its_own_file() {
        ExampleLoaded loaded = service.load("ES");

        assertThat(loaded.invoicesRegistered()).isEqualTo(1);
        assertThat(loaded.files()).singleElement().extracting(file -> file.file().name()).isEqualTo("spanish.n43");
        assertThat(invoices.registered).extracting(command -> command.number().value()).containsExactly("FV-2026-0001");
    }

    @Test
    void asking_for_a_country_with_no_example_is_refused_before_anything_is_loaded() {
        assertThatThrownBy(() -> service.load("DE"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("DE");

        assertThat(invoices.registered).isEmpty();
        assertThat(processing.sources).isEmpty();
    }

    @Test
    void the_files_can_be_downloaded_one_by_one_to_upload_them_by_hand() {
        assertThat(service.files()).extracting(ExampleFile::name).containsExactly("swiss.xml", "spanish.n43");
        assertThat(service.file("swiss.xml")).hasValueSatisfying(file ->
                assertThat(file.content()).asString().isEqualTo("swiss"));
        assertThat(service.file("nothing.xml")).isEmpty();
    }

    private static final class FakeExamples implements ExampleDataPort {
        @Override
        public ExampleData exampleData() {
            return new ExampleData(
                    List.of(invoice("F-2026-0001", SWISS), invoice("F-2026-0002", SWISS), invoice("FV-2026-0001", SPANISH)),
                    List.of(new ExampleFile("swiss.xml", "CH", "A Swiss camt.053", "swiss".getBytes()),
                            new ExampleFile("spanish.n43", "ES", "A Spanish Norma 43", "spanish".getBytes())));
        }

        private static RegisterInvoiceCommand invoice(String number, Iban account) {
            return new RegisterInvoiceCommand(InvoiceNumber.of(number), account, "Keller GmbH",
                    account.countryCode().equals("CH") ? Money.chf("100.00") : Money.eur("100.00"),
                    PaymentReference.none(), DUE);
        }
    }

    /** Stands in for the database: a number registered once is refused the second time (B24). */
    private static final class FakeInvoices implements RegisterInvoiceUseCase {
        private final List<RegisterInvoiceCommand> registered = new ArrayList<>();

        @Override
        public UUID register(RegisterInvoiceCommand command) {
            if (registered.stream().anyMatch(existing -> existing.number().equals(command.number()))) {
                throw new DuplicateInvoiceException("Invoice " + command.number() + " already exists",
                        new IllegalStateException("unique constraint"));
            }
            registered.add(command);
            return UUID.randomUUID();
        }
    }

    private static final class FakeProcessing implements ProcessStatementUseCase {
        private final List<ImportSource> sources = new ArrayList<>();
        private final List<String> bytes = new ArrayList<>();

        @Override
        public StatementProcessed process(ImportStatementCommand command) {
            sources.add(command.source());
            bytes.add(new String(read(command)));
            ImportedStatement statement = new ImportedStatement(UUID.randomUUID(), SWISS,
                    new Balance(Money.chf("0.00"), DUE), new Balance(Money.chf("100.00"), DUE), 1, 0);
            return new StatementProcessed(
                    new ImportResult(ImportResult.Outcome.IMPORTED, StatementFormat.CAMT053_V04, List.of(statement)),
                    List.of(ReconciliationRun.empty()));
        }

        private static byte[] read(ImportStatementCommand command) {
            try (var in = command.content().open()) {
                return in.readAllBytes();
            } catch (java.io.IOException e) {
                throw new IllegalStateException(e);
            }
        }
    }
}

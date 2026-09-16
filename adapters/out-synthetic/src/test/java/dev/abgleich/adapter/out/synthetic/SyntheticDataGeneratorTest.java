package dev.abgleich.adapter.out.synthetic;

import static org.assertj.core.api.Assertions.assertThat;

import dev.abgleich.adapter.out.synthetic.SyntheticDataset.Expected;
import dev.abgleich.application.port.in.RegisterInvoiceCommand;
import dev.abgleich.application.port.out.ExampleFile;
import dev.abgleich.domain.reference.CreditorReference;
import dev.abgleich.domain.reference.PaymentReference;
import dev.abgleich.domain.reference.QrReference;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.LongRange;
import org.junit.jupiter.api.Test;

class SyntheticDataGeneratorTest {

    private final SyntheticDataset dataset = SyntheticDataGenerator.defaultDataset();

    @Test
    void B21_same_seed_and_day_give_byte_identical_files() {
        SyntheticDataset again = SyntheticDataGenerator.defaultDataset();

        assertThat(again.files()).isEqualTo(dataset.files());
        assertThat(again.invoices()).isEqualTo(dataset.invoices());
    }

    @Test
    void another_seed_gives_other_amounts_but_the_same_cases() {
        SyntheticDataset other = new SyntheticDataGenerator(7, SyntheticDataGenerator.DEFAULT_DAY).generate();

        assertThat(other.files().getFirst()).isNotEqualTo(dataset.files().getFirst());
        assertThat(other.labels()).extracting(SyntheticDataset.Label::expected)
                .containsExactlyElementsOf(dataset.labels().stream().map(SyntheticDataset.Label::expected).toList());
    }

    @Test
    void every_case_of_the_catalogue_is_present() {
        assertThat(dataset.count(Expected.R1_AUTO_CONFIRM)).isEqualTo(8);
        assertThat(dataset.count(Expected.R2_PARTIAL_PAYMENT)).isEqualTo(1);
        assertThat(dataset.count(Expected.REFERENCE_TYPO_REVIEW)).isEqualTo(1);
        assertThat(dataset.count(Expected.R4_INVOICE_NUMBER_IN_TEXT)).isEqualTo(2);
        assertThat(dataset.count(Expected.R5_PAYER_NAME)).isEqualTo(2);
        assertThat(dataset.count(Expected.NO_INVOICE)).isEqualTo(3);
        assertThat(dataset.count(Expected.DEBIT)).isEqualTo(2);
        assertThat(dataset.invoices()).hasSize(16);
    }

    @Test
    void labelled_invoices_exist() {
        List<String> numbers = dataset.invoices().stream().map(invoice -> invoice.number().value()).toList();

        assertThat(dataset.labels()).filteredOn(label -> label.invoiceNumber() != null)
                .extracting(SyntheticDataset.Label::invoiceNumber)
                .allSatisfy(number -> assertThat(numbers).contains(number));
    }

    @Test
    void B41_files_say_the_data_is_synthetic_and_use_example_accounts_only() {
        String camt = new String(camt().content(), StandardCharsets.UTF_8);

        assertThat(camt).contains("Synthetic data").contains("CH4431999123000889012").contains("CH9300762011623852957");
        assertThat(dataset.invoices()).extracting(RegisterInvoiceCommand::creditorAccount).extracting(iban -> iban.value())
                .containsOnly("CH4431999123000889012", "CH9300762011623852957", "ES9121000418450200051332");
    }

    @Test
    void B18_B19_B20_norma43_is_latin1_with_80_character_records_and_crlf() {
        byte[] bytes = norma43().content();
        String text = new String(bytes, StandardCharsets.ISO_8859_1);

        assertThat(text.split("\r\n")).allSatisfy(record -> assertThat(record).hasSize(80));
        assertThat(text).endsWith("\r\n").doesNotContain("\n\n");
        assertThat(bytes).contains((byte) 0xD1);
        assertThat(text).contains("MUÑOZ");
        assertThat(text.lines().filter(line -> line.startsWith("22")))
                .as("two identical transfers of 605,00 (B16)")
                .filteredOn(line -> line.substring(28, 42).equals("00000000060500")).hasSize(2);
    }

    @Test
    void B05_references_of_invoices_have_valid_check_digits() {
        assertThat(dataset.invoices()).extracting(RegisterInvoiceCommand::reference)
                .filteredOn(reference -> reference != null)
                .allSatisfy(reference -> assertThat(reference)
                        .isInstanceOfAny(PaymentReference.Qrr.class, PaymentReference.Scor.class));
    }

    @Property
    void B04_a_typo_always_breaks_the_check_digits(@ForAll @LongRange(min = 1, max = 999_999_999_999L) long number) {
        String qrr = SyntheticReferences.qrr(number).value();
        String scor = SyntheticReferences.scor(number).value();

        assertThat(PaymentReference.parse(SyntheticReferences.withTypo(qrr))).isInstanceOf(PaymentReference.FreeText.class);
        assertThat(PaymentReference.parse(SyntheticReferences.withTypo(scor))).isInstanceOf(PaymentReference.FreeText.class);
        assertThat(QrReference.of(qrr).value()).hasSize(27);
        assertThat(CreditorReference.of(scor).value()).hasSize(16);
    }

    @Test
    void file_names_follow_the_day() {
        SyntheticDataset otherDay = new SyntheticDataGenerator(1, LocalDate.of(2026, 10, 1)).generate();

        assertThat(otherDay.files()).extracting(ExampleFile::name)
                .containsExactly("example-ch-camt053-20261001.xml", "example-es-norma43-20261001.n43");
    }

    private ExampleFile camt() {
        return dataset.files().get(0);
    }

    private ExampleFile norma43() {
        return dataset.files().get(1);
    }
}

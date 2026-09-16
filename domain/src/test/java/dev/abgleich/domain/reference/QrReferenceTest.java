package dev.abgleich.domain.reference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.constraints.IntRange;
import org.junit.jupiter.api.Test;

class QrReferenceTest {

    private static final String SPEC_EXAMPLE = "210000000003139471430009017";

    @Test
    void accepts_the_specification_example() {
        assertThat(QrReference.of(SPEC_EXAMPLE).value()).isEqualTo(SPEC_EXAMPLE);
    }

    @Test
    void B05_printed_form_with_spaces_equals_bank_file_form() {
        QrReference printed = QrReference.of("21 00000 00003 13947 14300 09017");
        QrReference fromXml = QrReference.of(SPEC_EXAMPLE);

        assertThat(printed).isEqualTo(fromXml);
        assertThat(printed.formatted()).isEqualTo("21 00000 00003 13947 14300 09017");
    }

    @Property
    void B04_reference_keeps_leading_zeros(@ForAll("referencesStartingWithZeros") String reference) {
        assertThat(QrReference.of(reference).value()).isEqualTo(reference).startsWith("00");
    }

    @Property
    void any_single_digit_typo_is_detected(
            @ForAll("validReferences") String reference,
            @ForAll @IntRange(min = 0, max = 26) int position,
            @ForAll @IntRange(min = 1, max = 9) int shift) {
        char original = reference.charAt(position);
        char typo = (char) ('0' + (original - '0' + shift) % 10);
        String mistyped = reference.substring(0, position) + typo + reference.substring(position + 1);

        assertThatThrownBy(() -> QrReference.of(mistyped)).isInstanceOf(InvalidReferenceException.class);
    }

    @Test
    void rejects_wrong_length() {
        assertThatThrownBy(() -> QrReference.of("21000000000313947143000901"))
                .isInstanceOf(InvalidReferenceException.class)
                .hasMessageContaining("27 digits");
    }

    @Test
    void rejects_letters() {
        assertThatThrownBy(() -> QrReference.of("21000000000313947143000901A"))
                .isInstanceOf(InvalidReferenceException.class);
    }

    @Provide
    Arbitrary<String> validReferences() {
        return Arbitraries.strings().numeric().ofLength(26).map(QrReferenceTest::withCheckDigit);
    }

    @Provide
    Arbitrary<String> referencesStartingWithZeros() {
        return Arbitraries.strings().numeric().ofLength(24).map(digits -> withCheckDigit("00" + digits));
    }

    private static String withCheckDigit(String first26) {
        return first26 + QrReference.checkDigitFor(first26);
    }
}

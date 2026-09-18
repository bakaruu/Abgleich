package dev.abgleich.domain.reference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.abgleich.domain.checksum.Mod97;
import org.junit.jupiter.api.Test;

class CreditorReferenceTest {

    @Test
    void accepts_the_iso_11649_example() {
        assertThat(CreditorReference.of("RF18539007547034").formatted()).isEqualTo("RF18 5390 0754 7034");
    }

    @Test
    void B05_lower_case_and_spaces_are_normalized() {
        assertThat(CreditorReference.of("rf18 5390 0754 7034"))
                .isEqualTo(CreditorReference.of("RF18539007547034"));
    }

    @Test
    void rejects_wrong_check_digits() {
        assertThatThrownBy(() -> CreditorReference.of("RF19539007547034"))
                .isInstanceOf(InvalidReferenceException.class)
                .hasMessageContaining("check digits");
    }

    @Test
    void rejects_references_without_rf_prefix() {
        assertThatThrownBy(() -> CreditorReference.of("XX18539007547034"))
                .isInstanceOf(InvalidReferenceException.class);
    }

    @Test
    void rejects_references_longer_than_25_characters() {
        assertThatThrownBy(() -> CreditorReference.of("RF18" + "1".repeat(22)))
                .isInstanceOf(InvalidReferenceException.class)
                .hasMessageContaining("between 5 and 25");
    }

    /** The ends of the allowed length are valid references, not off-by-one rejections. */
    @Test
    void the_shortest_and_the_longest_reference_are_both_accepted() {
        assertThat(CreditorReference.of("RF" + checkDigitsFor("A")).value()).hasSize(5);
        assertThat(CreditorReference.of("RF" + checkDigitsFor("1".repeat(21))).value()).hasSize(25);
    }

    /** Spaces are how banks print references; they are not part of the reference. */
    @Test
    void spaces_do_not_count_towards_the_length() {
        assertThat(CreditorReference.of("RF18 5390 0754 7034").value()).isEqualTo("RF18539007547034");
    }

    private static String checkDigitsFor(String rest) {
        for (int candidate = 2; candidate <= 98; candidate++) {
            String digits = candidate < 10 ? "0" + candidate : String.valueOf(candidate);
            if (Mod97.remainder(rest + "RF" + digits) == 1) {
                return digits + rest;
            }
        }
        throw new IllegalStateException("No check digits fit " + rest);
    }
}

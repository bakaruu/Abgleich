package dev.abgleich.domain.reference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
}

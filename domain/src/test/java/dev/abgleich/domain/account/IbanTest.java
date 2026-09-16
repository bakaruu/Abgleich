package dev.abgleich.domain.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.abgleich.domain.reference.InvalidReferenceException;
import org.junit.jupiter.api.Test;

class IbanTest {

    @Test
    void recognizes_a_swiss_qr_iban() {
        Iban iban = Iban.of("CH44 3199 9123 0008 8901 2");

        assertThat(iban.isQrIban()).isTrue();
        assertThat(iban.countryCode()).isEqualTo("CH");
    }

    @Test
    void a_regular_swiss_iban_is_not_a_qr_iban() {
        assertThat(Iban.of("CH93 0076 2011 6238 5295 7").isQrIban()).isFalse();
    }

    @Test
    void accepts_a_spanish_iban() {
        Iban iban = Iban.of("ES91 2100 0418 4502 0005 1332");

        assertThat(iban.countryCode()).isEqualTo("ES");
        assertThat(iban.isQrIban()).isFalse();
    }

    @Test
    void rejects_wrong_check_digits() {
        assertThatThrownBy(() -> Iban.of("CH45 3199 9123 0008 8901 2"))
                .isInstanceOf(InvalidReferenceException.class)
                .hasMessageContaining("check digits");
    }

    @Test
    void B41_to_string_never_exposes_the_full_iban() {
        Iban iban = Iban.of("CH4431999123000889012");

        assertThat(iban.toString())
                .isEqualTo("CH44 **** **** **** 8901 2")
                .doesNotContain("31999123");
        assertThat(String.valueOf(iban)).isEqualTo(iban.masked());
    }
}

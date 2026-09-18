package dev.abgleich.domain.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.abgleich.domain.reference.InvalidReferenceException;
import dev.abgleich.domain.checksum.Mod97;
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

    /** The QR-IID range is 30000 to 31999: both ends are QR-IBANs, one step outside is an ordinary account. */
    @Test
    void the_ends_of_the_qr_iban_range_are_qr_ibans() {
        assertThat(swissIbanWithIid(30000).isQrIban()).isTrue();
        assertThat(swissIbanWithIid(31999).isQrIban()).isTrue();
        assertThat(swissIbanWithIid(29999).isQrIban()).isFalse();
        assertThat(swissIbanWithIid(32000).isQrIban()).isFalse();
    }

    /** Only Swiss and Liechtenstein banks issue QR-IBANs, whatever the digits in that position say. */
    @Test
    void the_same_digits_in_another_country_are_not_a_qr_iban() {
        assertThat(ibanWithIid("ES", 30000).isQrIban()).isFalse();
    }

    private static Iban swissIbanWithIid(int iid) {
        return ibanWithIid("CH", iid);
    }

    private static Iban ibanWithIid(String country, int iid) {
        String rest = String.format("%05d", iid) + "000088901";
        String check = String.format("%02d", 98 - Mod97.remainder(rest + country + "00"));
        return Iban.of(country + check + rest);
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

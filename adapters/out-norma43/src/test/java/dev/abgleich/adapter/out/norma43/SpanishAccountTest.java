package dev.abgleich.adapter.out.norma43;

import static org.assertj.core.api.Assertions.assertThat;

import dev.abgleich.domain.account.Iban;
import org.junit.jupiter.api.Test;

class SpanishAccountTest {

    @Test
    void computes_national_and_iban_check_digits() {
        assertThat(SpanishAccount.toIban("2100", "0418", "0200051332")).isEqualTo(Iban.of("ES91 2100 0418 4502 0005 1332"));
    }

    @Test
    void check_digit_eleven_becomes_zero() {
        // 0049 1500: weighted sum 154 is a multiple of 11, so the first national check digit is 0.
        assertThat(SpanishAccount.toIban("0049", "1500", "0000012345").value()).isEqualTo("ES5600491500080000012345");
    }
}

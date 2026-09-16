package dev.abgleich.domain.reference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.abgleich.domain.reference.PaymentReference.FreeText;
import dev.abgleich.domain.reference.PaymentReference.None;
import dev.abgleich.domain.reference.PaymentReference.Qrr;
import dev.abgleich.domain.reference.PaymentReference.Scor;
import org.junit.jupiter.api.Test;

class PaymentReferenceTest {

    @Test
    void B05_detects_a_qr_reference_written_with_spaces() {
        PaymentReference reference = PaymentReference.parse("21 00000 00003 13947 14300 09017");

        assertThat(reference).isEqualTo(new Qrr(QrReference.of("210000000003139471430009017")));
    }

    @Test
    void B05_detects_a_creditor_reference_in_lower_case() {
        PaymentReference reference = PaymentReference.parse("rf18 5390 0754 7034");

        assertThat(reference).isEqualTo(new Scor(CreditorReference.of("RF18539007547034")));
    }

    @Test
    void B04_qr_reference_with_a_typo_is_kept_as_text_for_review() {
        PaymentReference reference = PaymentReference.parse("210000000003139471430009018");

        assertThat(reference).isEqualTo(new FreeText("210000000003139471430009018"));
    }

    @Test
    void creditor_reference_with_wrong_check_digits_is_kept_as_text() {
        assertThat(PaymentReference.parse("RF19539007547034")).isInstanceOf(FreeText.class);
    }

    @Test
    void other_references_are_free_text() {
        assertThat(PaymentReference.parse("  FV2026-0087 ")).isEqualTo(new FreeText("FV2026-0087"));
    }

    @Test
    void reference_longer_than_35_characters_is_rejected() {
        assertThatThrownBy(() -> PaymentReference.parse("x".repeat(36)))
                .isInstanceOf(InvalidReferenceException.class)
                .hasMessageContaining("35 characters");
    }

    @Test
    void missing_reference_is_none() {
        assertThat(PaymentReference.parse(null)).isInstanceOf(None.class);
        assertThat(PaymentReference.parse("   ")).isInstanceOf(None.class);
    }
}

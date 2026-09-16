package dev.abgleich.domain.matching.text;

import static org.assertj.core.api.Assertions.assertThat;

import dev.abgleich.domain.invoice.InvoiceNumber;
import java.math.BigDecimal;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.AlphaChars;
import net.jqwik.api.constraints.StringLength;
import org.junit.jupiter.api.Test;

class TextMatchingTest {

    @Test
    void jaro_winkler_matches_the_published_examples() {
        assertThat(TextSimilarity.jaroWinkler("MARTHA", "MARHTA")).isEqualByComparingTo("0.9611");
        assertThat(TextSimilarity.jaroWinkler("DWAYNE", "DUANE")).isEqualByComparingTo("0.8400");
        assertThat(TextSimilarity.jaroWinkler("DIXON", "DICKSONX")).isEqualByComparingTo("0.8133");
    }

    @Test
    void names_are_compared_without_accents_punctuation_or_legal_forms() {
        assertThat(TextSimilarity.nameSimilarity("Brunner & Co. AG", "BRUNNER UND CO")).isEqualByComparingTo("1.0000");
        assertThat(TextSimilarity.nameSimilarity("Zürcher Bäckerei Löwen", "ZURCHER BACKEREI LOWEN"))
                .isEqualByComparingTo("1.0000");
        assertThat(TextSimilarity.nameSimilarity("TALLERES RUIZ SL", "Talleres Ruiz S.L."))
                .isEqualByComparingTo("1.0000");
    }

    @Test
    void word_order_and_a_typo_keep_a_name_similar() {
        assertThat(TextSimilarity.nameSimilarity("JOSÉ MUÑOZ ÁLVAREZ", "MUNOZ ALVAREZ JOSE")).isEqualByComparingTo("1.0000");
        assertThat(TextSimilarity.nameSimilarity("Muster Handwerk GmbH", "MUSTER HANDWERCK")).isGreaterThanOrEqualTo(new BigDecimal("0.90"));
    }

    @Test
    void a_missing_or_different_word_is_not_the_same_payer() {
        assertThat(TextSimilarity.nameSimilarity("HOTEL MIRAMAR SA", "MIRAMAR")).isLessThan(new BigDecimal("0.90"));
        assertThat(TextSimilarity.nameSimilarity("PINTURAS SOL SA", "SOL")).isLessThan(new BigDecimal("0.90"));
    }

    @Test
    void different_companies_stay_below_the_name_threshold() {
        assertThat(TextSimilarity.nameSimilarity("Keller Elektro AG", "Seeblick Architektur GmbH"))
                .isLessThan(new BigDecimal("0.90"));
        assertThat(TextSimilarity.nameSimilarity("PINTURAS SOL SA", "PINTURAS LUNA SA"))
                .isLessThan(new BigDecimal("0.90"));
    }

    @Property
    void B01_similarity_is_symmetric_and_between_zero_and_one(
            @ForAll @AlphaChars @StringLength(max = 30) String first,
            @ForAll @AlphaChars @StringLength(max = 30) String second) {
        BigDecimal forward = TextSimilarity.nameSimilarity(first, second);

        assertThat(forward).isBetween(BigDecimal.ZERO, BigDecimal.ONE);
        assertThat(TextSimilarity.nameSimilarity(second, first)).isEqualByComparingTo(forward);
    }

    @Test
    void one_edit_distance() {
        assertThat(TextSimilarity.withinOneEdit("RF18539007547034", "RF18539007547035")).isTrue();
        assertThat(TextSimilarity.withinOneEdit("RF18539007547034", "RF1853900754703")).isTrue();
        assertThat(TextSimilarity.withinOneEdit("RF18539007547034", "RF185390075470344")).isTrue();
        assertThat(TextSimilarity.withinOneEdit("RF18539007547034", "RF18539007547034")).isTrue();
        assertThat(TextSimilarity.withinOneEdit("RF18539007547034", "RF18539007547143")).isFalse();
        assertThat(TextSimilarity.withinOneEdit("RF18539007547034", "RF185390075470")).isFalse();
    }

    @Test
    void finds_invoice_numbers_announced_in_several_languages() {
        assertThat(InvoiceMentions.mentions("TRANSF TALLERES RUIZ SL FRA 87", InvoiceNumber.of("FV-2026-0087"))).isTrue();
        assertThat(InvoiceMentions.mentions("Rechnung 143 vielen Dank", InvoiceNumber.of("F-2026-0143"))).isTrue();
        assertThat(InvoiceMentions.mentions("PAGO FACTURAS 91 Y 92", InvoiceNumber.of("FV-2026-0092"))).isTrue();
        assertThat(InvoiceMentions.mentions("Invoice no. 0143", InvoiceNumber.of("F-2026-0143"))).isTrue();
        assertThat(InvoiceMentions.mentions("ref FV2026-0087", InvoiceNumber.of("FV-2026-0087"))).isTrue();
        assertThat(InvoiceMentions.mentions("Paiement facture F-2026-0143", InvoiceNumber.of("F-2026-0143"))).isTrue();
    }

    @Test
    void numbers_without_an_invoice_word_are_not_invoice_mentions() {
        assertThat(InvoiceMentions.mentions("TRANSF 87 EUR SEPTIEMBRE", InvoiceNumber.of("FV-2026-0087"))).isFalse();
        assertThat(InvoiceMentions.mentions("CUOTA 15 09 2026", InvoiceNumber.of("FV-2026-0015"))).isFalse();
        assertThat(InvoiceMentions.mentions("FRA 870", InvoiceNumber.of("FV-2026-0087"))).isFalse();
        assertThat(InvoiceMentions.mentions(null, InvoiceNumber.of("FV-2026-0087"))).isFalse();
    }
}

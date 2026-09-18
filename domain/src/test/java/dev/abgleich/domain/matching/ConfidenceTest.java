package dev.abgleich.domain.matching;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

/** B03: confidence is an exact decimal between 0.00 and 1.00, and the ends of that range are valid values. */
class ConfidenceTest {

    @Test
    void both_ends_of_the_range_are_valid() {
        assertThat(Confidence.of("0.00").value()).isEqualByComparingTo("0.00");
        assertThat(Confidence.of("1.00").value()).isEqualByComparingTo("1.00");
    }

    @Test
    void nothing_outside_the_range_is() {
        assertThatThrownBy(() -> Confidence.of("-0.01")).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("between 0.00 and 1.00");
        assertThatThrownBy(() -> Confidence.of("1.01")).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("between 0.00 and 1.00");
    }

    @Test
    void a_third_decimal_would_be_a_precision_nobody_has() {
        assertThatThrownBy(() -> Confidence.of("0.855")).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at most 2 decimals");
    }

    @Test
    void the_scale_is_normalised_so_equal_confidences_compare_equal() {
        assertThat(Confidence.of("0.9").value()).isEqualByComparingTo(new BigDecimal("0.90"));
        assertThat(Confidence.of("0.90")).isEqualTo(Confidence.of("0.9"));
    }

    @Test
    void every_rule_has_a_confidence_inside_the_range() {
        assertThat(MatchRule.values()).allSatisfy(rule -> {
            assertThat(rule.confidence().value()).isBetween(new BigDecimal("0.00"), new BigDecimal("1.00"));
            assertThat(rule.confidence().value().scale()).isEqualTo(2);
        });
    }
}

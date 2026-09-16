package dev.abgleich.domain.money;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.Currency;
import java.util.List;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.BigRange;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.constraints.Scale;
import org.junit.jupiter.api.Test;

class MoneyTest {

    @Test
    void B02_equality_ignores_scale() {
        Money fromXml = Money.chf("1250.0");
        Money fromInvoice = Money.chf("1250.00");

        assertThat(fromXml).isEqualTo(fromInvoice);
        assertThat(fromXml.hashCode()).isEqualTo(fromInvoice.hashCode());
    }

    @Test
    void B02_more_than_two_decimals_are_rejected_not_rounded() {
        assertThatThrownBy(() -> Money.eur("10.005"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("more than 2 decimals");
    }

    @Test
    void B03_allocation_spreads_leftover_cents() {
        List<Money> parts = Money.chf("100.00").allocate(3);

        assertThat(parts).containsExactly(Money.chf("33.34"), Money.chf("33.33"), Money.chf("33.33"));
    }

    @Property
    void B03_allocation_preserves_total(
            @ForAll @BigRange(min = "-1000000", max = "1000000") @Scale(2) BigDecimal amount,
            @ForAll @IntRange(min = 1, max = 12) int parts) {
        Money total = new Money(amount, Money.EUR);

        List<Money> shares = total.allocate(parts);

        Money sum = shares.stream().reduce(Money.zero(Money.EUR), Money::add);
        assertThat(sum).isEqualTo(total);
        assertThat(shares).hasSize(parts);
        BigDecimal largest = shares.stream().map(Money::amount).max(BigDecimal::compareTo).orElseThrow();
        BigDecimal smallest = shares.stream().map(Money::amount).min(BigDecimal::compareTo).orElseThrow();
        assertThat(largest.subtract(smallest)).isLessThanOrEqualTo(new BigDecimal("0.01"));
    }

    @Test
    void B06_different_currencies_cannot_be_added() {
        assertThatThrownBy(() -> Money.chf("480.00").add(Money.eur("480.00")))
                .isInstanceOf(CurrencyMismatchException.class)
                .hasMessage("Cannot combine CHF with EUR");
    }

    @Test
    void B06_same_amount_in_different_currencies_is_not_equal() {
        assertThat(Money.chf("480.00")).isNotEqualTo(Money.eur("480.00"));
    }

    @Test
    void B06_different_currencies_cannot_be_compared() {
        assertThatThrownBy(() -> Money.chf("1.00").compareTo(Money.eur("1.00")))
                .isInstanceOf(CurrencyMismatchException.class);
    }

    @Test
    void currencies_without_two_minor_units_are_rejected() {
        assertThatThrownBy(() -> Money.of("100", Currency.getInstance("JPY")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("JPY");
    }

    @Test
    void subtraction_can_produce_an_outstanding_negative_balance() {
        Money outstanding = Money.chf("1200.00").subtract(Money.chf("2000.00"));

        assertThat(outstanding).isEqualTo(Money.chf("-800.00"));
        assertThat(outstanding.isNegative()).isTrue();
    }

    @Test
    void prints_currency_and_plain_amount() {
        assertThat(Money.chf("1250")).hasToString("CHF 1250.00");
    }
}

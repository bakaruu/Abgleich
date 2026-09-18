package dev.abgleich.domain.matching;

import static org.assertj.core.api.Assertions.assertThat;

import dev.abgleich.domain.account.Iban;
import dev.abgleich.domain.invoice.Invoice;
import dev.abgleich.domain.invoice.InvoiceNumber;
import dev.abgleich.domain.money.Money;
import dev.abgleich.domain.reference.PaymentReference;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * B29: the search behind R6 must never run away. A debtor with forty open invoices has more than a trillion
 * combinations, so the search is bounded three ways — how many invoices it looks at, how many it may combine,
 * and how many partial sums it may evaluate — and each bound is checked here at the point where it bites.
 */
class SubsetSearchTest {

    private static final Iban ACCOUNT = Iban.of("CH9300762011623852957");
    private static final LocalDate DUE = LocalDate.of(2026, 9, 30);

    @Test
    void finds_the_combinations_that_add_up_exactly() {
        SubsetSearch search = new SubsetSearch(5, 20_000, 3);

        List<List<Invoice>> found = search.find(
                List.of(invoice("F-1", "100.00"), invoice("F-2", "200.00"), invoice("F-3", "300.00")),
                Money.chf("300.00"), 20);

        assertThat(found).singleElement().extracting(group -> group.stream().map(i -> i.number().value()).toList())
                .isEqualTo(List.of("F-1", "F-2"));
    }

    @Test
    void a_single_invoice_is_never_a_combination() {
        SubsetSearch search = new SubsetSearch(5, 20_000, 3);

        List<List<Invoice>> found = search.find(List.of(invoice("F-1", "300.00"), invoice("F-2", "900.00")),
                Money.chf("300.00"), 20);

        assertThat(found).isEmpty();
    }

    @Test
    void it_stops_after_the_number_of_proposals_a_person_can_compare() {
        SubsetSearch search = new SubsetSearch(5, 20_000, 2);

        List<List<Invoice>> found = search.find(List.of(
                invoice("F-1", "100.00"), invoice("F-2", "100.00"), invoice("F-3", "100.00"),
                invoice("F-4", "100.00"), invoice("F-5", "100.00"), invoice("F-6", "100.00")),
                Money.chf("200.00"), 20);

        assertThat(found).as("many pairs add up to 200, only the first two are offered").hasSize(2);
    }

    @Test
    void it_never_combines_more_invoices_than_the_policy_allows() {
        SubsetSearch pairsOnly = new SubsetSearch(2, 20_000, 3);

        List<List<Invoice>> found = pairsOnly.find(
                List.of(invoice("F-1", "50.00"), invoice("F-2", "50.00"), invoice("F-3", "50.00")),
                Money.chf("150.00"), 20);

        assertThat(found).as("three invoices of 50 add up, but only pairs may be combined").isEmpty();
    }

    @Test
    void it_only_looks_at_the_first_invoices_of_a_long_ledger() {
        SubsetSearch search = new SubsetSearch(5, 20_000, 3);
        List<Invoice> many = List.of(invoice("F-1", "10.00"), invoice("F-2", "20.00"), invoice("F-3", "30.00"),
                invoice("F-4", "40.00"));

        assertThat(search.find(many, Money.chf("30.00"), 2))
                .as("with only two invoices searched, 10 + 20 is still found")
                .hasSize(1);
        assertThat(search.find(many, Money.chf("70.00"), 2))
                .as("30 + 40 is out of reach when only two invoices are searched")
                .isEmpty();
    }

    @Test
    void it_gives_up_when_it_has_spent_its_budget() {
        SubsetSearch generous = new SubsetSearch(5, 20_000, 3);
        SubsetSearch broke = new SubsetSearch(5, 2, 3);
        List<Invoice> invoices = List.of(invoice("F-1", "10.00"), invoice("F-2", "20.00"), invoice("F-3", "30.00"),
                invoice("F-4", "40.00"), invoice("F-5", "50.00"));

        assertThat(generous.find(invoices, Money.chf("90.00"), 20)).isNotEmpty();
        assertThat(generous.visited()).isPositive();
        assertThat(generous.exhausted()).isFalse();

        assertThat(broke.find(invoices, Money.chf("90.00"), 20)).isEmpty();
        assertThat(broke.visited()).isEqualTo(2);
        assertThat(broke.exhausted()).isTrue();
    }

    @Test
    void invoices_with_nothing_outstanding_are_not_searched() {
        SubsetSearch search = new SubsetSearch(5, 20_000, 3);
        Invoice settled = invoice("F-2", "100.00").withConfirmedPayment(Money.chf("100.00"));

        assertThat(search.find(List.of(invoice("F-1", "100.00"), settled, invoice("F-3", "100.00")),
                Money.chf("200.00"), 20))
                .singleElement()
                .extracting(group -> group.stream().map(i -> i.number().value()).toList())
                .isEqualTo(List.of("F-1", "F-3"));
    }

    private static Invoice invoice(String number, String amount) {
        return Invoice.register(UUID.randomUUID(), InvoiceNumber.of(number), ACCOUNT, "Keller GmbH",
                Money.chf(amount), PaymentReference.none(), DUE);
    }
}

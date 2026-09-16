package dev.abgleich.domain.matching;

import dev.abgleich.domain.invoice.Invoice;
import dev.abgleich.domain.money.Money;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Finds groups of two to {@code maxSize} invoices whose outstanding amounts add up exactly to a payment.
 *
 * <p>Forty open invoices have 2⁴⁰ subsets. The search is bounded three ways so one payment can never
 * hang the reconciliation (B29): only the first {@code maxSearched} invoices, at most {@code maxSize} per
 * group, and a budget of visited partial sums. Invoices are sorted by amount so a partial sum above the
 * payment prunes every larger continuation. No wall clock is involved, so the result is deterministic.
 */
final class SubsetSearch {

    private final int maxSize;
    private final int budget;
    private final int maxResults;
    private int visited;

    SubsetSearch(int maxSize, int budget, int maxResults) {
        this.maxSize = maxSize;
        this.budget = budget;
        this.maxResults = maxResults;
    }

    List<List<Invoice>> find(List<Invoice> invoices, Money target, int maxSearched) {
        List<Invoice> sorted = invoices.stream()
                .filter(invoice -> invoice.outstanding().isPositive())
                .limit(maxSearched)
                .sorted(Comparator.comparing(Invoice::outstanding).thenComparing(invoice -> invoice.number().value()))
                .toList();
        List<List<Invoice>> results = new ArrayList<>();
        search(sorted, 0, new ArrayList<>(), Money.zero(target.currency()), target, results);
        return results;
    }

    /** How many partial sums the last search evaluated. */
    int visited() {
        return visited;
    }

    boolean exhausted() {
        return visited >= budget;
    }

    private void search(List<Invoice> invoices, int start, List<Invoice> chosen, Money sum, Money target,
            List<List<Invoice>> results) {
        for (int i = start; i < invoices.size(); i++) {
            if (visited >= budget || results.size() >= maxResults) {
                return;
            }
            visited++;
            Invoice invoice = invoices.get(i);
            Money newSum = sum.add(invoice.outstanding());
            int comparison = newSum.compareTo(target);
            if (comparison > 0) {
                return;
            }
            chosen.add(invoice);
            if (comparison == 0) {
                if (chosen.size() >= 2) {
                    results.add(List.copyOf(chosen));
                }
            } else if (chosen.size() < maxSize) {
                search(invoices, i + 1, chosen, newSum, target, results);
            }
            chosen.removeLast();
        }
    }
}

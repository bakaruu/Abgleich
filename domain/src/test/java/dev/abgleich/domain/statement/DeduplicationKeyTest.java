package dev.abgleich.domain.statement;

import static org.assertj.core.api.Assertions.assertThat;

import dev.abgleich.domain.account.Iban;
import dev.abgleich.domain.money.Direction;
import dev.abgleich.domain.money.Money;
import dev.abgleich.domain.reference.PaymentReference;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

class DeduplicationKeyTest {

    private static final LocalDate DAY = LocalDate.of(2026, 9, 15);

    @Test
    void B16_identical_lines_are_both_kept() {
        List<DeduplicationKey> keys = statement(transfer("605.00", "PAGO FACTURAS 91 Y 92"),
                transfer("605.00", "PAGO FACTURAS 91 Y 92")).deduplicationKeys();

        assertThat(keys).hasSize(2).doesNotHaveDuplicates();
        assertThat(keys.get(0).value()).endsWith(":1");
        assertThat(keys.get(1).value()).endsWith(":2");
    }

    @Test
    void B16_same_file_imported_again_gives_the_same_keys() {
        Statement first = statement(transfer("605.00", "PAGO FACTURAS 91 Y 92"), transfer("1815.00", "FRA 87"));
        Statement again = statement(transfer("605.00", "PAGO FACTURAS 91 Y 92"), transfer("1815.00", "FRA 87"));

        assertThat(again.deduplicationKeys()).isEqualTo(first.deduplicationKeys());
    }

    @Test
    void B16_any_different_field_gives_a_different_key() {
        List<DeduplicationKey> keys = statement(transfer("605.00", "PAGO FACTURAS 91 Y 92"),
                transfer("605.00", "PAGO FACTURAS 91 Y 93"),
                transfer("605.01", "PAGO FACTURAS 91 Y 92")).deduplicationKeys();

        assertThat(keys).extracting(DeduplicationKey::value).allMatch(value -> value.endsWith(":1"));
        assertThat(keys).doesNotHaveDuplicates();
    }

    @Test
    void bank_reference_is_the_key_when_the_bank_provides_one() {
        StatementEntry camtEntry = new StatementEntry(Money.eur("10.00"), Direction.CREDIT, DAY, null,
                "BNK20260915000123", false, List.of());

        assertThat(statement(camtEntry).deduplicationKeys())
                .containsExactly(new DeduplicationKey("BANK:BNK20260915000123"));
    }

    @Test
    void derived_key_fits_the_database_column() {
        DeduplicationKey key = statement(transfer("605.00", "x".repeat(500))).deduplicationKeys().getFirst();

        assertThat(key.value().length()).isLessThanOrEqualTo(DeduplicationKey.MAX_LENGTH);
    }

    private static Statement statement(StatementEntry... entries) {
        Money closing = Money.eur("0.00");
        for (StatementEntry entry : entries) {
            closing = closing.add(entry.amount());
        }
        return new Statement(Iban.of("ES9121000418450200051332"), null,
                new Balance(Money.eur("0.00"), DAY), new Balance(closing, DAY), List.of(entries));
    }

    private static StatementEntry transfer(String amount, String concept) {
        TransactionDetail detail = new TransactionDetail(null, PaymentReference.none(), concept, null, null, null);
        return new StatementEntry(Money.eur(amount), Direction.CREDIT, DAY, null, null, false, List.of(detail));
    }
}

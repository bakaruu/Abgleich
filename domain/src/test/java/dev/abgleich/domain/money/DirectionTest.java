package dev.abgleich.domain.money;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class DirectionTest {

    @Test
    void B10_debits_never_pay_invoices() {
        assertThat(Direction.DEBIT.canPayInvoices()).isFalse();
        assertThat(Direction.CREDIT.canPayInvoices()).isTrue();
    }
}

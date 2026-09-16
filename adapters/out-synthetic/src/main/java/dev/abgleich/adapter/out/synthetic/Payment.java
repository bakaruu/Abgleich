package dev.abgleich.adapter.out.synthetic;

import dev.abgleich.domain.money.Money;
import java.util.Objects;

/**
 * One incoming payment as a bank file shows it.
 *
 * @param structuredReference a QRR or RF reference as the payer typed it, possibly with a typo, or {@code null}
 * @param remittance free text, or {@code null}
 */
record Payment(Money amount, String payer, String structuredReference, String remittance) {

    Payment {
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(payer, "payer");
    }
}

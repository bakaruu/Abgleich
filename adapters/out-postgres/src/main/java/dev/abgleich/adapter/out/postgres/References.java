package dev.abgleich.adapter.out.postgres;

import dev.abgleich.domain.reference.PaymentReference;

/** Payment references are stored as their normalized text; {@link PaymentReference#parse} reads them back. */
final class References {

    private References() {
    }

    static String text(PaymentReference reference) {
        return switch (reference) {
            case PaymentReference.Qrr qrr -> qrr.value().value();
            case PaymentReference.Scor scor -> scor.value().value();
            case PaymentReference.FreeText text -> text.value();
            case PaymentReference.None none -> null;
        };
    }
}

package dev.abgleich.domain.reference;

import java.util.Locale;
import java.util.Objects;

/**
 * The reference a payer attached to a payment: a Swiss QR reference, an ISO 11649 creditor
 * reference, some other text, or nothing at all.
 *
 * <p>Bank files often carry references with typos. {@link #parse(String)} never fails on them:
 * a reference that looks like a QRR or SCOR but has invalid check digits is kept as
 * {@link FreeText}, so the payment goes to review instead of being lost (B04, B05).
 */
public sealed interface PaymentReference {

    record Qrr(QrReference value) implements PaymentReference {
        public Qrr {
            Objects.requireNonNull(value, "value");
        }
    }

    record Scor(CreditorReference value) implements PaymentReference {
        public Scor {
            Objects.requireNonNull(value, "value");
        }
    }

    /** Any other reference, at most 35 characters like every reference field in ISO 20022. */
    record FreeText(String value) implements PaymentReference {

        public static final int MAX_LENGTH = 35;

        public FreeText {
            Objects.requireNonNull(value, "value");
            value = value.strip();
            if (value.isEmpty()) {
                throw new InvalidReferenceException("Free text reference must not be blank");
            }
            if (value.length() > MAX_LENGTH) {
                throw new InvalidReferenceException("Payment reference must not be longer than " + MAX_LENGTH + " characters");
            }
        }
    }

    record None() implements PaymentReference {
    }

    static PaymentReference none() {
        return new None();
    }

    /**
     * Detects the kind of reference. Never fails on typos, but a text longer than 35 characters is
     * not a reference at all and throws {@link InvalidReferenceException}.
     */
    static PaymentReference parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return none();
        }
        String compact = raw.replaceAll("\\s", "");
        if (compact.matches("\\d{27}")) {
            try {
                return new Qrr(QrReference.of(compact));
            } catch (InvalidReferenceException typo) {
                return new FreeText(raw);
            }
        }
        if (compact.toUpperCase(Locale.ROOT).startsWith("RF")) {
            try {
                return new Scor(CreditorReference.of(compact));
            } catch (InvalidReferenceException notScor) {
                return new FreeText(raw);
            }
        }
        return new FreeText(raw);
    }
}

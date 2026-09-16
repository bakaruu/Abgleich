package dev.abgleich.domain.reference;

import dev.abgleich.domain.checksum.Mod97;
import java.util.Locale;
import java.util.Objects;

/**
 * ISO 11649 creditor reference (SCOR): "RF" + 2 check digits + 1 to 21 alphanumerics,
 * validated with modulo 97. Used across Europe. Normalized to upper case without spaces (B05).
 */
public record CreditorReference(String value) {

    private static final int MIN_LENGTH = 5;
    private static final int MAX_LENGTH = 25;

    public CreditorReference {
        Objects.requireNonNull(value, "value");
        value = value.replaceAll("\\s", "").toUpperCase(Locale.ROOT);
        if (value.length() < MIN_LENGTH || value.length() > MAX_LENGTH) {
            throw new InvalidReferenceException("Creditor reference must have between 5 and 25 characters");
        }
        if (!value.matches("RF\\d{2}[A-Z0-9]+")) {
            throw new InvalidReferenceException("Creditor reference must start with RF and 2 check digits");
        }
        if (Mod97.remainder(value.substring(4) + value.substring(0, 4)) != 1) {
            throw new InvalidReferenceException("Creditor reference check digits are invalid");
        }
    }

    public static CreditorReference of(String value) {
        return new CreditorReference(value);
    }

    /** Printed form in groups of four: "RF18 5390 0754 7034". */
    public String formatted() {
        return value.replaceAll("(.{4})(?!$)", "$1 ");
    }

    @Override
    public String toString() {
        return formatted();
    }
}

package dev.abgleich.domain.account;

import dev.abgleich.domain.checksum.Mod97;
import dev.abgleich.domain.reference.InvalidReferenceException;
import java.util.Locale;
import java.util.Objects;

/**
 * International bank account number, validated with modulo 97.
 *
 * <p>{@link #toString()} is masked on purpose so an IBAN never ends up in full in a log
 * line (B41). Use {@link #value()} only where the full number is really needed.
 */
public record Iban(String value) {

    private static final int QR_IID_FROM = 30000;
    private static final int QR_IID_TO = 31999;

    public Iban {
        Objects.requireNonNull(value, "value");
        value = value.replaceAll("\\s", "").toUpperCase(Locale.ROOT);
        if (!value.matches("[A-Z]{2}\\d{2}[A-Z0-9]{11,30}")) {
            throw new InvalidReferenceException("IBAN has an invalid format");
        }
        if (Mod97.remainder(value.substring(4) + value.substring(0, 4)) != 1) {
            throw new InvalidReferenceException("IBAN check digits are invalid");
        }
    }

    public static Iban of(String value) {
        return new Iban(value);
    }

    public String countryCode() {
        return value.substring(0, 2);
    }

    /** Swiss/Liechtenstein QR-IBAN: institution id (positions 5-9) between 30000 and 31999. */
    public boolean isQrIban() {
        if (!countryCode().equals("CH") && !countryCode().equals("LI")) {
            return false;
        }
        String iid = value.substring(4, 9);
        if (!iid.chars().allMatch(Character::isDigit)) {
            return false;
        }
        int number = Integer.parseInt(iid);
        return number >= QR_IID_FROM && number <= QR_IID_TO;
    }

    /** "CH44 **** **** **** 8901 2": keeps country, check digits and the last five characters. */
    public String masked() {
        int visibleTail = 5;
        String middle = "*".repeat(value.length() - 4 - visibleTail);
        String raw = value.substring(0, 4) + middle + value.substring(value.length() - visibleTail);
        return raw.replaceAll("(.{4})(?!$)", "$1 ");
    }

    @Override
    public String toString() {
        return masked();
    }
}

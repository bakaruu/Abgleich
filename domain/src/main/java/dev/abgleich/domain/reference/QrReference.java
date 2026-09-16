package dev.abgleich.domain.reference;

import java.util.Objects;

/**
 * Swiss QR reference (QRR): 27 digits, the last one a recursive modulo 10 check digit.
 *
 * <p>Kept as text so leading zeros survive (B04). Whitespace is removed on creation, so the
 * printed form "21 00000 00003 ..." equals the value found in bank files (B05).
 */
public record QrReference(String value) {

    private static final int LENGTH = 27;
    private static final int[] CARRY_TABLE = {0, 9, 4, 6, 8, 2, 7, 1, 3, 5};

    public QrReference {
        Objects.requireNonNull(value, "value");
        value = value.replaceAll("\\s", "");
        if (value.length() != LENGTH || !value.chars().allMatch(Character::isDigit)) {
            throw new InvalidReferenceException("QR reference must have exactly 27 digits");
        }
        int expected = checkDigitFor(value.substring(0, LENGTH - 1));
        if (value.charAt(LENGTH - 1) - '0' != expected) {
            throw new InvalidReferenceException("QR reference check digit is invalid");
        }
    }

    public static QrReference of(String value) {
        return new QrReference(value);
    }

    /** Computes the recursive modulo 10 check digit for the first 26 digits. */
    public static int checkDigitFor(String digits) {
        int carry = 0;
        for (int i = 0; i < digits.length(); i++) {
            char c = digits.charAt(i);
            if (!Character.isDigit(c)) {
                throw new InvalidReferenceException("QR reference must contain only digits");
            }
            carry = CARRY_TABLE[(carry + (c - '0')) % 10];
        }
        return (10 - carry) % 10;
    }

    /** Human-readable form as printed on a QR-bill: "21 00000 00003 13947 14300 09017". */
    public String formatted() {
        StringBuilder out = new StringBuilder(value.substring(0, 2));
        for (int i = 2; i < LENGTH; i += 5) {
            out.append(' ').append(value, i, i + 5);
        }
        return out.toString();
    }

    @Override
    public String toString() {
        return formatted();
    }
}

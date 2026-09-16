package dev.abgleich.domain.checksum;

/** ISO 7064 MOD 97-10 remainder, shared by IBAN and creditor reference validation. */
public final class Mod97 {

    private Mod97() {
    }

    /**
     * Letters count as two digits (A = 10 ... Z = 35); digits count as themselves.
     * Callers validate the alphabet first; any other character is a programming error.
     */
    public static int remainder(String alphanumeric) {
        int remainder = 0;
        for (int i = 0; i < alphanumeric.length(); i++) {
            int value = Character.digit(alphanumeric.charAt(i), 36);
            if (value < 0) {
                throw new IllegalArgumentException("Only letters and digits are allowed");
            }
            remainder = value < 10
                    ? (remainder * 10 + value) % 97
                    : (remainder * 100 + value) % 97;
        }
        return remainder;
    }
}

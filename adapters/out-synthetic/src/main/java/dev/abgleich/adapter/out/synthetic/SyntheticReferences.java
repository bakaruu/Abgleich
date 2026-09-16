package dev.abgleich.adapter.out.synthetic;

import dev.abgleich.domain.checksum.Mod97;
import dev.abgleich.domain.reference.CreditorReference;
import dev.abgleich.domain.reference.QrReference;

/** Valid references built from numbers, so every example reference passes the real check digits. */
final class SyntheticReferences {

    private SyntheticReferences() {
    }

    /** A Swiss QR reference: 26 digits made from the number, plus the recursive modulo 10 check digit. */
    static QrReference qrr(long number) {
        String digits = String.format("%026d", number);
        return QrReference.of(digits + QrReference.checkDigitFor(digits));
    }

    /** An ISO 11649 creditor reference with a 12-character body: "RF" + check digits + body = 16 characters. */
    static CreditorReference scor(long number) {
        String body = String.format("%012d", number);
        int check = 98 - Mod97.remainder(body + "RF00");
        return CreditorReference.of(String.format("RF%02d%s", check, body));
    }

    /** The same reference with its last digit changed: what a payer typing it by hand produces (B04). */
    static String withTypo(String reference) {
        char last = reference.charAt(reference.length() - 1);
        char wrong = last == '9' ? '0' : (char) (last + 1);
        return reference.substring(0, reference.length() - 1) + wrong;
    }
}

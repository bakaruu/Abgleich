package dev.abgleich.adapter.out.norma43;

import dev.abgleich.domain.account.Iban;
import dev.abgleich.domain.checksum.Mod97;

/**
 * Builds the IBAN of a Spanish account from the parts Norma 43 gives: bank (4), branch (4) and
 * account number (10). The two national check digits (DC) are not in the file and are computed
 * with the official modulo 11 weights.
 */
final class SpanishAccount {

    private static final int[] WEIGHTS = {1, 2, 4, 8, 5, 10, 9, 7, 3, 6};

    private SpanishAccount() {
    }

    static Iban toIban(String bank, String branch, String account) {
        String bban = bank + branch + checkDigit("00" + bank + branch) + checkDigit(account) + account;
        int ibanCheck = 98 - Mod97.remainder(bban + "ES00");
        return Iban.of("ES%02d%s".formatted(ibanCheck, bban));
    }

    private static int checkDigit(String tenDigits) {
        int sum = 0;
        for (int i = 0; i < WEIGHTS.length; i++) {
            sum += (tenDigits.charAt(i) - '0') * WEIGHTS[i];
        }
        int digit = 11 - sum % 11;
        return switch (digit) {
            case 11 -> 0;
            case 10 -> 1;
            default -> digit;
        };
    }
}
